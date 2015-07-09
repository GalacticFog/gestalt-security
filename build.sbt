import com.typesafe.sbt.packager.docker._

name := """gestalt-security"""

organization := "com.galacticfog"

version := "1.1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala,SbtNativePackager)

scalaVersion := "2.11.4"

maintainer in Docker := "Chris Baker <chris@galacticfog.com>"

dockerBaseImage := "galacticfog.artifactoryonline.com/play-with-ssl-utils"

dockerUpdateLatest := true

dockerExposedPorts := Seq(9000)

dockerRepository := Some("galacticfog.artifactoryonline.com")

publishTo := Some("Artifactory Realm" at "http://galacticfog.artifactoryonline.com/galacticfog/libs-snapshots-local")

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache,
  ws
)

resolvers ++= Seq(
  "gestalt" at "http://galacticfog.artifactoryonline.com/galacticfog/libs-snapshots-local",
  "snapshots" at "http://scala-tools.org/repo-snapshots",
  "releases"  at "http://scala-tools.org/repo-releases")

//
// Adds project name to prompt like in a Play project
//
shellPrompt in ThisBuild := { state => "\033[0;36m" + Project.extract(state).currentRef.project + "\033[0m] " }

// ----------------------------------------------------------------------------
// This adds a new command to sbt: type 'flyway' to execute
// 'flywayClean' and 'flywayMigrate' in sequence
// ----------------------------------------------------------------------------
lazy val FlywayRebuild = Command.command("flyway") { state =>
	"flywayClean" :: "flywayMigrate" :: state
}

commands += FlywayRebuild

lazy val GenDataModel = Command.command("generateModel") { state => 
 "scalikejdbcGenForce org GestaltOrgRepository" :: "scalikejdbcGenForce api_account APIAccountRepository" :: "scalikejdbcGenForce app AppRepository" :: "scalikejdbcGenForce user_account UserAccountRepository" :: "scalikejdbcGenForce right_grant RightGrantRepository" :: "scalikejdbcGenForce user_group UserGroupRepository" :: "scalikejdbcGenForce account_x_group GroupMembershipRepository" :: "scalikejdbcGenForce app_x_group AppGroupAssignmentRepository" :: "scalikejdbcGenForce app_x_account AppAccountAssignmentRepository" :: state
}

commands += GenDataModel

scalikejdbcSettings


// ----------------------------------------------------------------------------
// Gestalt Security SDK
// ----------------------------------------------------------------------------

libraryDependencies ++= Seq(
  "com.galacticfog" % "gestalt-security-sdk-scala_2.11" % "0.1.1-SNAPSHOT" withSources()
)

// ----------------------------------------------------------------------------
// Apache Shiro
// ----------------------------------------------------------------------------

libraryDependencies += "org.apache.shiro" % "shiro-core" % "1.2.3"


// ----------------------------------------------------------------------------
// ScalikeJDBC
// ----------------------------------------------------------------------------

libraryDependencies += "org.scalikejdbc" % "scalikejdbc_2.11" % "2.2.3"

libraryDependencies += "org.scalikejdbc" %% "scalikejdbc-test"   % "2.2.3"   % "test"

// ----------------------------------------------------------------------------
// PostgreSQL
// ----------------------------------------------------------------------------

libraryDependencies += "org.postgresql" % "postgresql" % "9.3-1102-jdbc4"


// ----------------------------------------------------------------------------
// Play JSON
// ----------------------------------------------------------------------------

libraryDependencies += "com.typesafe.play" % "play-json_2.11" % "2.4.0-M2"


// ----------------------------------------------------------------------------
// Specs 2
// ----------------------------------------------------------------------------

libraryDependencies += "junit" % "junit" % "4.12" % "test"

libraryDependencies += "org.specs2" % "specs2-junit_2.11" % "2.4.15" % "test"

libraryDependencies += "org.specs2" %% "specs2-core" % "2.4.15" % "test"


// ----------------------------------------------------------------------------
// Flyway
// ----------------------------------------------------------------------------

libraryDependencies += "com.h2database" % "h2" % "1.4.186"

libraryDependencies += "org.flywaydb" % "flyway-core" % "3.2.1"


// ----------------------------------------------------------------------------
// Flyway Plugin Settings
// ----------------------------------------------------------------------------

seq(flywaySettings: _*)

flywayUrl := "jdbc:postgresql://***REMOVED***:5432/gestalt-security-1.1"

flywayUser := "gestaltdev"

flywayPassword := "***REMOVED***"

// ----------------------------------------------------------------------------
// Docker customization
// ----------------------------------------------------------------------------
// dockerCommands := dockerCommands.value.filterNot {
//   case ExecCmd("ENTRYPOINT", args @ _*) => true
//   case cmd                              => false
// }
// 
// dockerCommands ++= Seq(
//   ExecCmd("ENTRYPOINT", 
//     s"/opt/bin/ssl_and_launch.sh",
//     s"${(defaultLinuxInstallLocation in Docker).value}/bin/${executableScriptName.value}")
// )
