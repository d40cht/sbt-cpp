sbtPlugin := true

name := "sbt-cpp"

organization := "org.seacourt.build"

version := "0.0.11"

scalaVersion := "2.9.2"

publishMavenStyle := false

publishTo := Some(Resolver.file("file", new File("./releases")))

libraryDependencies += "com.typesafe" % "config" % "1.0.0"

libraryDependencies += "com.sleepycat" % "je" % "4.0.92"

scalacOptions ++= Seq( "-deprecation", "-optimize" )



