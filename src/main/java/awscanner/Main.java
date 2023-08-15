/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package awscanner;

import awscanner.analyzers.AnalyzerConfig;
import awscanner.analyzers.UnusedEbsVolumes;
import awscanner.analyzers.UnusedSnapshots;
import awscanner.ec2.EBSInfo;
import awscanner.ec2.InstanceInfo;
import awscanner.ec2.ScanFunctions;
import awscanner.ec2.SnapshotInfo;
import awscanner.efs.EFSInfo;
import awscanner.graph.ResourceGraph;
import awscanner.report.OwnerReport;
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

import java.io.File;
import java.util.ArrayList;
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

    @Option( names = { "--pricing-profile" }, description = "Credential profile name" )
    private String pricing_profile = null;

    @Option( names = { "-c", "--color" }, type = Boolean.class,
        negatable = true, defaultValue = "true",
        description = "Enable or disable color console output" )
    private boolean color_output;

    @Option( names = { "--delete-obvious" }, type = Boolean.class,
        defaultValue = "false",
        description = "Delete obviously unused resources. When set to false, these are flagged \"❗️\"" )
    private boolean delete_obvious = false;

    @Option( names = { "--obvious-days" }, type = Integer.class,
        defaultValue = "21",
        description = "Resources are considered 'obvious' when unused for this many days" )
    private int obvious_days = 21;

    @Option( names = { "--export" }, type = File.class )
    private File export_file = null;

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "0..*")
    private List<OwnerReportArgs> owner_reports_args;       // Must be uninitialized,
                                                            // otherwise Picocli errors

    static class OwnerReportArgs {
        @Option( names = { "--report-by-owner-tag" },
            description = "Tag name which indicates the owner of a resource",
            required = true )
        String owner_tag = null;

        @Option( names = { "--owner-tag-split-by" },
            description = "If the tag specified in `--report-by-owner-tag` can indicate " +
                "multiple owners, this should be set to the delimiter." )
        String owner_tag_delimiter = null;

        @Option( names = { "--report-data-file" },
            description = "If specified, report output will be written to the specified file in " +
                "json format. If not specified, human-friendly output is written to stdout.",
            defaultValue = Option.NULL_VALUE )
        File report_data_file = null;
    }


    @Override
    public Integer call() throws Exception {
        if ( pricing_profile == null ) {
            pricing_profile = profiles[ 0 ];
        }

        ColorWriter writer = ColorWriter.create( color_output );

        if ( delete_obvious ) {
            writer.print( "WARNING: ", ColorWriter.Color.RED );
            writer.print( "--delete-obvious flag is set (days=" + obvious_days + "). Will start " +
                "possibly destructive run in " );
            for( int i = 10; i > 0; i-- ) {
                writer.print( i + "... " );
                Thread.sleep( 1000 );
            }
            writer.println( "now.");
        }

        for ( String profile : profiles ) {
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

        if ( owner_reports_args == null ) owner_reports_args = List.of();
        List<OwnerReport> owner_reports = owner_reports_args.stream()
            .map( args -> new OwnerReport( args.owner_tag, args.owner_tag_delimiter, args.report_data_file ) )
            .toList();

        ResourceGraph graph = new ResourceGraph();
        boolean any_pricing_enabled = false;
        for ( Future<RegionInfo> future : futures ) {
            RegionInfo region_info = future.get();
            graph.appendRegionInfo( region_info );

            if ( region_info.pricing_enabled() ) any_pricing_enabled = true;

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
            for ( EFSInfo efs : region_info.efs().values() ) {
                ColorWriter.Color color = ColorWriter.NONE;
                if ( efs.provisioned_throughput_mibps() != null ) {
                    color = ColorWriter.Color.YELLOW;
                }
                writer.println( "  " + efs, color );

            }

            AnalyzerConfig analyzer_config = new AnalyzerConfig(delete_obvious, obvious_days);
            Ec2Client ec2_client = Ec2Client.builder()
                    .region( region_info.region() )
                    .credentialsProvider( cred_provider )
                    .build();
            UnusedEbsVolumes.analyze( region_info, writer, ec2_client, analyzer_config );
            UnusedSnapshots.analyze( region_info, writer, ec2_client, analyzer_config );

//          System.out.println( region_info );
//			System.out.printf( "---- %1$s ----\n%2$s", region_info.region(), region_info );
        }

        if ( export_file != null ) {
            graph.export( export_file );
            System.out.println( "Export to " + export_file + " complete." );
        }

        if ( !owner_reports.isEmpty() && !any_pricing_enabled ) {
            System.err.println( "Owner report is not available because pricing lookups are disabled. " +
                "See earlier error message for details." );
            System.exit( -1 );
        }
        for ( var report : owner_reports ) {
            report.process( graph );
            report.report();
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
