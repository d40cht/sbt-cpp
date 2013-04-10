resolvers += Resolver.file("SBT cpp repo", file("../../releases"))( Patterns("[organisation]/[module]_[scalaVersion]_[sbtVersion]/[revision]/[artifact]-[revision].[ext]") )

addSbtPlugin("org.seacourt.build" % "sbt-cpp" % "0.0.6")
