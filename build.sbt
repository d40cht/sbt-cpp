sbtPlugin := true

name := "sbt-cpp"

organization := "org.seacourt.build"

version := "0.0.34-SNAPSHOT"

scalaVersion := "2.9.3"

publishMavenStyle := false

publishTo := Some(Resolver.file("file", new File("./releases")))

libraryDependencies += "com.typesafe" % "config" % "1.0.1"

libraryDependencies += "com.sleepycat" % "je" % "4.0.92"

libraryDependencies += "org.scalatest" %% "scalatest" % "1.9.1"

scalacOptions ++= Seq( "-deprecation" )



