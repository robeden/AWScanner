/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package awscanner;

import awscanner.analyzers.UnusedEbsVolumes;
import awscanner.analyzers.UnusedSnapshots;
import awscanner.ec2.EBSInfo;
import awscanner.ec2.InstanceInfo;
import awscanner.ec2.SnapshotInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


@Command( name = "awscanner", mixinStandardHelpOptions = true )
public class Main implements Callable<Integer> {
    private static final Region[] STS_HUNT_REGIONS = { Region.US_EAST_1, Region.US_GOV_EAST_1 };

    @Option( names = { "-p", "--profile" }, description = "Credential profile name",
        required = true )
    private String[] profiles;

    @Option( names = { "--pricing-profile" }, description = "Credential profile name",
        required = false )
    private String pricing_profile = null;

    @Option( names = { "-c", "--color" }, type = Boolean.class,
        negatable = true, defaultValue = "true",
        description = "Enable or disable color console output" )
    private boolean color_output;


    @Override
    public Integer call() throws Exception {
        if ( pricing_profile == null ) {
            pricing_profile = profiles[ 0 ];
        }

        ColorWriter writer = ColorWriter.create( color_output );

        for ( int i = 0; i < profiles.length; i++ ) {
            String profile = profiles[ i ];

            doProfile( writer, profile, pricing_profile );
        }

        return 0;
    }

    private void doProfile( ColorWriter writer, String profile, String pricing_profile ) throws Exception {
        writer.println();
        writer.println("==== " + profile + " ====", ColorWriter.BLUE);
        writer.println();

        AwsCredentialsProvider cred_provider = ProfileCredentialsProvider.create( profile );
        AwsCredentialsProvider pricing_cred_provider =
            pricing_profile == null ? cred_provider : ProfileCredentialsProvider.create( pricing_profile );

        String cred_description = "Credential";
        try {
            cred_provider.resolveCredentials();

            if ( pricing_cred_provider != cred_provider ) {
                cred_description = "Pricing credential";
                pricing_cred_provider.resolveCredentials();
            }
        }
        catch( Exception ex ) {
            System.err.println( cred_description + " error: " + ex.getMessage() );
            System.exit( -1 );
            return;
        }

        GetCallerIdentityResponse response = huntForCallerIdentity( cred_provider );
//        System.out.printf( "Caller: user=%1$s account=%2$s arn=%3$s%n",
//            response.userId(), response.account(), response.arn() );

        String partition = response.arn().split( ":" )[ 1 ];
//        System.out.println( "Partition: " + partition );

        ExecutorService executor = Executors.newWorkStealingPool();

        List<Region> regions = loadRegions( partition.equals( "aws-us-gov" ), cred_provider );

        List<Future<RegionInfo>> futures = regions.stream()
            .map( r -> executor.submit(
                new RegionScanner( r, cred_provider, pricing_cred_provider, executor, response.account() ) ) )
            .toList();

//		List<Future<RegionInfo>> futures = executor.invokeAll( regions.stream()
//			.map( r -> new RegionScanner( r, cred_provider, executor ) )
//			.toList() );

//        System.out.println( "Loading..." );
        for ( Future<RegionInfo> future : futures ) {
            RegionInfo region_info = future.get();

            writer.println( region_info.region().id(), ColorWriter.BLUE );

            for ( InstanceInfo instance : region_info.instances().values() ) {
                writer.println( "  " + instance.toString(),
                    instance.isRunning() ? ColorWriter.GREEN : ColorWriter.RED );
            }
            for ( EBSInfo ebs : region_info.ebs_volumes().values() ) {
                ColorWriter.Color color = ColorWriter.RED;
                if ( ebs.isAttached() ) {
                    color = ColorWriter.GREEN;
                }
                else if ( ebs.snapshot_id() != null ) {
                    if ( region_info.snapshots().containsKey( ebs.snapshot_id() ) ) {
                        color = ColorWriter.YELLOW;
                    }
                }
                else if ( region_info.images().containsKey( ebs.id() ) ) {
                    color = ColorWriter.YELLOW;
                }
                writer.println( "  " + ebs, color );
            }
            for ( SnapshotInfo snapshot : region_info.snapshots().values() ) {
                ColorWriter.Color color = ColorWriter.NONE;
                if ( snapshot.volume_id() == null ||
                    !region_info.ebs_volumes().containsKey( snapshot.volume_id() ) ) {
                    color = ColorWriter.Color.RED;
                }
                writer.println( "  " + snapshot, color );
            }

            Ec2Client ec2_client = Ec2Client.builder()
                    .region( region_info.region() )
                    .credentialsProvider( cred_provider )
                    .build();
            UnusedEbsVolumes.analyze( region_info, writer, ec2_client );
            UnusedSnapshots.analyze( region_info, writer, ec2_client );

//          System.out.println( region_info );
//			System.out.printf( "---- %1$s ----\n%2$s", region_info.region(), region_info );
        }
    }


    private List<Region> loadRegions( boolean is_gov, AwsCredentialsProvider cred_provider ) {
        try ( Ec2Client client = Ec2Client.builder()
            .region( is_gov ? Region.US_GOV_EAST_1 : Region.US_EAST_1 )
            .credentialsProvider( cred_provider )
            .build() ) {

            return client.describeRegions().regions().stream()
                .filter( r -> !r.optInStatus().equals( "not-opted-in" ) )
                .map( r -> Region.of( r.regionName() ) )
                .toList();
        }
    }


    /**
     * Loops through supported bootstrap regions to try to find the correct partition for STS connection.
     */
    private GetCallerIdentityResponse huntForCallerIdentity( AwsCredentialsProvider cred_provider ) {
        StsException last_exception = null;
        for ( Region r : STS_HUNT_REGIONS ) {
            try ( StsClient client = StsClient.builder()
                .credentialsProvider( cred_provider )
                .region( r )
                .build() ) {

                return client.getCallerIdentity();
            }
            catch ( StsException ex ) {
                last_exception = ex;
            }
        }
        //noinspection ConstantConditions
        throw last_exception;
    }


    public static void main( String[] args ) {
        int exitCode = new CommandLine( new Main() ).execute( args );
        System.exit( exitCode );
    }
}
