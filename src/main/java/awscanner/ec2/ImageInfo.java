package awscanner.ec2;

import java.util.Map;


public record ImageInfo(String id, Map<String,String> tags, String name, String architecture,
                        String kernel, String platform, String image_type, String hypervisor,
                        String virtualization_type, String tpm_support, String boot_mode,
                        String state, Boolean public_launch_permissions) {
}
