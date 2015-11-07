import com.typesafe.sbt.packager.docker._

name := """gestalt-security"""

organization := "com.galacticfog"

version := "1.2.0"

maintainer in Docker := "Chris Baker <chris@galacticfog.com>"

dockerUpdateLatest := true

dockerRepository := Some("galacticfog.artifactoryonline.com")

lazy val root = (project in file(".")).enablePlugins(PlayScala,SbtNativePackager)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws
)

resolvers ++= Seq(
  "gestalt" at "http://galacticfog.artifactoryonline.com/galacticfog/libs-snapshots-local",
  "snapshots" at "http://scala-tools.org/repo-snapshots",
  "releases"  at "http://scala-tools.org/repo-releases",
  "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"
)

credentials ++= {
  (for {
    realm <- sys.env.get("GESTALT_RESOLVER_REALM")
    username <- sys.env.get("GESTALT_RESOLVER_USERNAME")
    resolverUrlStr <- sys.env.get("GESTALT_RESOLVER_URL")
    resolverUrl <- scala.util.Try{url(resolverUrlStr)}.toOption
    password <- sys.env.get("GESTALT_RESOLVER_PASSWORD")
  } yield {
    Seq(Credentials(realm, resolverUrl.getHost, username, password))
  }) getOrElse(Seq())
}

resolvers ++= {
  sys.env.get("GESTALT_RESOLVER_URL") map {
    url => Seq("gestalt-resolver" at url)
  } getOrElse(Seq())
}

//
// Adds project name to prompt like in a Play project
//
shellPrompt in ThisBuild := { state => "\033[0;36m" + Project.extract(state).currentRef.project + "\033[0m] " }

lazy val GenDataModel = Command.command("generateModel") { state => 
 "scalikejdbcGenForce directory GestaltDirectoryRepository" :: "scalikejdbcGenForce org GestaltOrgRepository" :: "scalikejdbcGenForce account UserAccountRepository" :: "scalikejdbcGenForce account_group UserGroupRepository" :: "scalikejdbcGenForce account_store_type AccountStoreTypeRepository" :: "scalikejdbcGenForce account_store_mapping AccountStoreMappingRepository" :: "scalikejdbcGenForce account_x_group GroupMembershipRepository" :: "scalikejdbcGenForce app GestaltAppRepository" :: "scalikejdbcGenForce api_credential APICredentialRepository" :: "scalikejdbcGenForce right_grant RightGrantRepository" :: state
}

commands += GenDataModel

scalikejdbcSettings

// ----------------------------------------------------------------------------
// Gestalt Security SDK
// ----------------------------------------------------------------------------

libraryDependencies ++= Seq(
  "com.galacticfog" %% "gestalt-security-sdk-scala" % "0.2.0" withSources()
)

// ----------------------------------------------------------------------------
// Play JSON
// ----------------------------------------------------------------------------

libraryDependencies += "com.typesafe.play" %% "play-json" % "2.4.0-M2"

// ----------------------------------------------------------------------------
// ScalikeJDBC
// ----------------------------------------------------------------------------

libraryDependencies += "org.scalikejdbc" %% "scalikejdbc" % "2.2.9"

libraryDependencies += "org.scalikejdbc" %% "scalikejdbc-test"   % "2.2.9"   % "test"

// ----------------------------------------------------------------------------
// PostgreSQL
// ----------------------------------------------------------------------------

libraryDependencies += "org.postgresql" % "postgresql" % "9.3-1102-jdbc4"

// ----------------------------------------------------------------------------
// Specs 2
// ----------------------------------------------------------------------------

libraryDependencies += "junit" % "junit" % "4.12" % "test"

libraryDependencies += "org.specs2" %% "specs2-junit" % "2.4.15" % "test"

libraryDependencies += "org.specs2" %% "specs2-core" % "2.4.15" % "test"

libraryDependencies += "org.apache.commons" % "commons-dbcp2" % "2.1"

libraryDependencies += "org.flywaydb" % "flyway-core" % "3.2.1"

// ----------------------------------------------------------------------------
// Flyway Plugin Settings
// ----------------------------------------------------------------------------

val hostname = sys.env.get( "DB_HOST" ) getOrElse "localhost"

val dbname = sys.env.get( "DB_NAME" ) getOrElse "gestalt-security"

lazy val migration = (project in file("migration")).
  settings(flywaySettings: _*).
  settings(
    flywayUrl := s"jdbc:postgresql://$hostname:5432/$dbname",
    flywayUser := sys.env.get( "DB_USER" ) getOrElse "dbUser",
    flywayPassword := sys.env.get( "DB_PASSWORD" ) getOrElse "dbS3cr3t",
    flywayLocations := Seq("filesystem:conf/db/migration"),
    flywayTarget := "4",
    flywayPlaceholders := Map(
      "root_username" -> sys.env.get( "ROOT_USERNAME" ).getOrElse("admin"),
      "root_password" -> sys.env.get( "ROOT_PASSWORD" ).getOrElse("letmein")
    )
  ).
  settings(
    libraryDependencies += "org.flywaydb" % "flyway-core" % "3.2.1"
  )
  
