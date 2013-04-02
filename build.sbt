sbtPlugin := true

name := "sbt-cpp"

organization := "org.seacourt.build"

version := "0.0.1"

scalaVersion := "2.9.2"

publishMavenStyle := false

publishTo := Some(Resolver.file("file", new File("./releases")))

scalacOptions ++= Seq( "-deprecation", "-optimize" )



