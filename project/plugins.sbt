resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"

// The Play plugin
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.5.10")

// web plugins

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.1")

// Driver needed here for scalike mapper.

addSbtPlugin("org.scalikejdbc" %% "scalikejdbc-mapper-generator" % "2.5.0")

//
// Flyway
//

addSbtPlugin("org.flywaydb" % "flyway-sbt" % "3.1")

resolvers += "Flyway" at "https://flywaydb.org/repo"

