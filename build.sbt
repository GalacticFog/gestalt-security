name := """gestalt-security"""

version := "2.4.2"

lazy val root = (project in file(".")).
  enablePlugins(PlayScala,SbtNativePackager).
  enablePlugins(BuildInfoPlugin).
  enablePlugins(AshScriptPlugin).
  settings(
    buildInfoKeys := Seq[BuildInfoKey](
      name, version, scalaVersion, sbtVersion,
      "builtBy" -> System.getProperty("user.name"),
      "gitHash" -> new java.lang.Object(){
              override def toString(): String = {
                      try { 
                    val extracted = new java.io.InputStreamReader(
                              java.lang.Runtime.getRuntime().exec("git rev-parse HEAD").getInputStream())                         
                    (new java.io.BufferedReader(extracted)).readLine()
                      } catch {      case t: Throwable => "get git hash failed"    }
              }}.toString()
    ),
    buildInfoPackage := "com.galacticfog.gestalt.security",
    buildInfoUsePackageAsPath := true
  )

buildInfoOptions += BuildInfoOption.BuildTime

buildInfoOptions += BuildInfoOption.ToJson

scalaVersion := "2.11.7"

scalacOptions ++= Seq(
  "-unchecked", "-deprecation", "-feature",
  "-language:postfixOps", "-language:implicitConversions"
)


import com.typesafe.sbt.packager.docker._
maintainer in Docker := "Chris Baker <chris@galacticfog.com>"
dockerBaseImage := "openjdk:8-jre-alpine"
dockerExposedPorts := Seq(9000)
dockerCommands := dockerCommands.value.flatMap {
  case cmd@Cmd("FROM",_) => List(
    cmd,
    Cmd("RUN", "apk upgrade && rm -rf /var/cache/apk/*")
  )
  case other => List(other)
}

resolvers ++= Seq(
  "gestalt-snapshots" at "https://galacticfog.artifactoryonline.com/galacticfog/libs-snapshots-local",
  "gestalt-releases" at "https://galacticfog.artifactoryonline.com/galacticfog/libs-releases-local",
  "snapshots" at "http://scala-tools.org/repo-snapshots",
  "releases"  at "http://scala-tools.org/repo-releases",
  "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases",
  "splunk-releases" at "http://splunk.artifactoryonline.com/splunk/ext-releases-local"
)

libraryDependencies ++= Seq(
  jdbc,
  ws,
  filters,
  specs2 % Test,
  "net.codingwell"  %% "scala-guice" 					 % "4.1.0",
  "org.scalikejdbc" %% "scalikejdbc" % "2.2.9",
  "org.scalikejdbc" %% "scalikejdbc-test" % "2.2.9" % Test,
  "org.postgresql" % "postgresql" % "9.3-1102-jdbc4",
  "com.splunk.logging" % "splunk-library-javalogging" % "1.5.0"
)

libraryDependencies ++= Seq("org.specs2" %% "specs2-matcher-extra" % "3.6.6" % "test")

//
// Adds project name to prompt like in a Play project
//
shellPrompt in ThisBuild := { state => "\033[0;36m" + Project.extract(state).currentRef.project + "\033[0m] " }

lazy val GenDataModel = Command.command("generateModel") { state => 
 "scalikejdbcGenForce directory_type GestaltDirectoryTypeRepository" :: "scalikejdbcGenForce directory GestaltDirectoryRepository" :: "scalikejdbcGenForce org GestaltOrgRepository" :: "scalikejdbcGenForce account UserAccountRepository" :: "scalikejdbcGenForce account_group UserGroupRepository" :: "scalikejdbcGenForce account_store_type AccountStoreTypeRepository" :: "scalikejdbcGenForce account_store_mapping AccountStoreMappingRepository" :: "scalikejdbcGenForce account_x_group GroupMembershipRepository" :: "scalikejdbcGenForce app GestaltAppRepository" :: "scalikejdbcGenForce api_credential APICredentialRepository" :: "scalikejdbcGenForce right_grant RightGrantRepository" :: "scalikejdbcGenForce token TokenRepository" :: "scalikejdbcGenForce initialization_settings InitSettingsRepository" :: state 
}

commands += GenDataModel

scalikejdbcSettings

// ----------------------------------------------------------------------------
// Gestalt Security SDK
// ----------------------------------------------------------------------------

libraryDependencies ++= Seq(
  "com.galacticfog" %% "gestalt-security-sdk-scala" % "2.4.1" withSources(),
  "com.galacticfog" %% "gestalt-ldapdirectory" % "1.2.1",
  "com.galacticfog" % "gestalt-license-keymgr" % "1.2.1-SNAPSHOT"
)

libraryDependencies += "org.flywaydb" % "flyway-core" % "3.2.1"

libraryDependencies += "org.apache.commons" % "commons-dbcp2" % "2.1"

// libraryDependencies += "org.slf4j" % "slf4j-simple"   % "1.6.1" % "test"
