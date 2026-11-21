import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import java.security.MessageDigest
import java.util.Properties

plugins {
  java
  id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.learning"
version = providers.gradleProperty("pluginVersion").get()

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

val localProperties = Properties().apply {
  val contents = providers.fileContents(layout.projectDirectory.file("local.properties")).asText.orNull
  if (contents != null) contents.reader().use { load(it) }
}

dependencies {
  intellijPlatform {
    val localIdea = providers.gradleProperty("ideaHome").orNull ?: localProperties.getProperty("ideaHome")
    if (localIdea != null) {
      require(file(localIdea).isDirectory) { "ideaHome must point to an installed IntelliJ IDEA: $localIdea" }
      logger.info("Using local IntelliJ IDEA SDK: $localIdea")
      local(localIdea)
    } else intellijIdea(providers.gradleProperty("platformVersion").get())
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.Plugin.Java)
  }
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get().toInt())) } }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

intellijPlatform {
  buildSearchableOptions = false
  pluginConfiguration {
    version = project.version.toString()
    ideaVersion {
      sinceBuild = providers.gradleProperty("sinceBuild")
      untilBuild = providers.gradleProperty("untilBuild")
    }
  }
  pluginVerification {
    ides {
      val target = providers.gradleProperty("verificationVersion").orNull
      if (target == null) current() else create("IU", target)
    }
    failureLevel = listOf(
      VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
      VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
      VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
      VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
      VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
      VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
    )
  }
  signing {
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    privateKey = providers.environmentVariable("PRIVATE_KEY")
    privateKeyFile.set(layout.file(providers.environmentVariable("SIGNING_PRIVATE_KEY_FILE").map { file(it) }))
    certificateChainFile.set(layout.file(providers.environmentVariable("SIGNING_CERTIFICATE_FILE").map { file(it) }))
    password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
  }
  publishing { token = providers.environmentVariable("PUBLISH_TOKEN") }
}

tasks.withType<AbstractArchiveTask>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}
tasks.jar {
  from(listOf("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}
tasks.test {
  useJUnit()
  exclude("**/NavigationServerTest.class")
  systemProperty("java.awt.headless", "true")
}

// These tests use a real loopback HTTP server and an injected navigator, not JUnit fixtures.
val httpTest = tasks.register<JavaExec>("httpTest") {
  dependsOn(tasks.testClasses)
  classpath = sourceSets.test.get().runtimeClasspath
  // Standalone JavaExec has no IDE classloader to supply platform-provided libraries.
  classpath += sourceSets.main.get().compileClasspath
  mainClass.set("dev.learning.navigator.NavigationServerTest")
  enableAssertions = true
}
tasks.check { dependsOn(httpTest) }

val pluginChecksum = tasks.register("pluginChecksum") {
  dependsOn(tasks.buildPlugin)
  val archive = tasks.buildPlugin.flatMap { it.archiveFile }
  inputs.file(archive)
  val checksum = archive.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") }
  outputs.file(checksum)
  doLast {
    val file = archive.get().asFile
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    checksum.get().writeText(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + "  " + file.name + "\n")
  }
}

tasks.verifyPluginSignature { dependsOn(tasks.signPlugin) }

val signedPluginChecksum = tasks.register("signedPluginChecksum") {
  dependsOn(tasks.verifyPluginSignature)
  val archive = tasks.signPlugin.flatMap { it.signedArchiveFile }
  inputs.file(archive)
  val checksum = archive.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") }
  outputs.file(checksum)
  doLast {
    val file = archive.get().asFile
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    checksum.get().writeText(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + "  " + file.name + "\n")
  }
}

// Publishing is always an explicit action, with verification and signing as prerequisites.
tasks.publishPlugin {
  dependsOn(tasks.check, tasks.verifyPlugin, tasks.signPlugin)
  doFirst {
    fun supplied(name: String) = !System.getenv(name).isNullOrBlank()
    check((supplied("CERTIFICATE_CHAIN") || supplied("SIGNING_CERTIFICATE_FILE")) &&
        (supplied("PRIVATE_KEY") || supplied("SIGNING_PRIVATE_KEY_FILE")) && supplied("PUBLISH_TOKEN")) {
      "Publishing requires signing certificate/key (content or file variables) and PUBLISH_TOKEN. See docs/PUBLISHING.md."
    }
  }
}
