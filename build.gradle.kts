
plugins {
    // Apply the application plugin to add support for building a CLI application in Java.
    application
}

tasks.compileJava {
    options.release.set(21)
}
//java {
//    toolchain {
//        languageVersion.set(JavaLanguageVersion.of(17))
//    }
//}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("software.amazon.awssdk:bom:[2.20.162,3.)"))
    implementation("software.amazon.awssdk:cloudfront")
    implementation("software.amazon.awssdk:dynamodb")
    implementation("software.amazon.awssdk:ec2")
    implementation("software.amazon.awssdk:efs")
    implementation("software.amazon.awssdk:lambda")
    implementation("software.amazon.awssdk:pricing")
    implementation("software.amazon.awssdk:rds")
    implementation("software.amazon.awssdk:route53")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sns")
    implementation("software.amazon.awssdk:sts")
    runtimeOnly("software.amazon.awssdk:sso")           // Needed for SSO-based profiles
    runtimeOnly("software.amazon.awssdk:ssooidc")       // Needed for SSO-based profiles

    implementation("com.sun.mail:jakarta.mail:2.0.1")

    implementation("info.picocli:picocli:4.7.5")        // CLI option parsing
    implementation("com.diogonunes:JColor:5.5.1")       // Terminal colors
    implementation("com.squareup.moshi:moshi:1.15.0")   // Json parsing

    implementation("org.jgrapht:jgrapht-core:1.5.2")    // Graph
    implementation("org.jgrapht:jgrapht-io:1.5.2")      // Graph
    constraints {
        implementation("org.apache.commons:commons-text:1.10") {
            because("version 1.8 pulled from jgraph-io has CVE-2022-42889")
        }
    }

    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

application {
    mainClass.set("awscanner.Main")
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
