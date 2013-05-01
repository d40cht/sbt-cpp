package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{mutable, immutable}

/**
  * Gcc and compatible (e.g. Clang) compilers
  */
case class GccCompiler(
    override val toolPaths : Seq[File],
    override val defaultLibraryPaths : Seq[File],
    override val defaultIncludePaths : Seq[File],
    val ccExe : File,
    val cxxExe : File,
    val archiverExe : File,
    val linkerExe : File,
    override val ccDefaultFlags : Seq[String] = Seq(),
    override val cxxDefaultFlags : Seq[String] = Seq(),
    override val linkDefaultFlags : Seq[String] = Seq() ) extends Compiler
{
    
    
    def findHeaderDependencies( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".d") )
    { depFile =>
    
        val tmpDepFile = buildDirectory / (sourceFile.base + ".dt")
        val depCmd = Seq[String]( ccExe.toString, "-MF", tmpDepFile.toString, "-M", sourceFile.toString ) ++ compilerFlags ++ includePaths.map( ip => "-I" + ip.toString ) ++ systemIncludePaths.map( ip => "-isystem" + ip.toString )
        
        val depResult = runProcess( log, depCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
        
        // Strip off any trailing backslash characters from the output
        val depFileLines = IO.readLines( tmpDepFile ).map( _.replace( "\\", "" ) )
    
        // Drop the first column and split on spaces to get all the files (potentially several per line )
        val allFiles = depFileLines.flatMap( _.split(" ").drop(1) ).map( x => new File(x.trim) )
        
        IO.write( depFile, allFiles.mkString("\n") )
        
        reportFileGenerated( log, depFile, quiet )
    }
    
    def ccCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".o") )
    { outputFile =>
    
        val buildCmd = Seq[String]( ccExe.toString, "-fPIC", "-c", "-o", outputFile.toString, sourceFile.toString ) ++ compilerFlags ++ includePaths.map( ip => "-I" + ip.toString ) ++ systemIncludePaths.map( ip => "-isystem" + ip.toString )

        runProcess( log, buildCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
        
        reportFileGenerated( log, outputFile, quiet )
    }
    
    def cxxCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".o") )
    { outputFile =>
    
        val buildCmd = Seq[String]( cxxExe.toString, "-fPIC", "-c", "-o", outputFile.toString, sourceFile.toString ) ++ compilerFlags ++ includePaths.map( ip => "-I" + ip.toString ) ++ systemIncludePaths.map( ip => "-isystem" + ip.toString )

        runProcess( log, buildCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
        
        reportFileGenerated( log, outputFile, quiet )
    }
    
    def buildStaticLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".a") )
        { outputFile =>
        
            val arCmd = Seq[String]( archiverExe.toString, "-c", "-r", outputFile.toString ) ++ objectFiles.map( _.toString )

            runProcess( log, arCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
            
            reportFileGenerated( log, outputFile, quiet )
        }
        
    def buildSharedLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".so") )
        { outputFile =>
        
            val cmd = Seq[String]( cxxExe.toString, "-shared", "-o", outputFile.toString ) ++ objectFiles.map( _.toString )

            runProcess( log, cmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
            
            reportFileGenerated( log, outputFile, quiet )
        }
        
    def buildExecutable( log : Logger, buildDirectory : File, exeName : String, linkFlags : Seq[String], linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / exeName )
        { outputFile =>
            val linkCmd = Seq[String]( linkerExe.toString, "-o" + outputFile.toString ) ++ linkFlags ++ inputFiles.map( _.toString ) ++ linkPaths.map( lp => "-L" + lp ) ++ linkLibraries.map( ll => "-l" + ll )
            
            runProcess( log, linkCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
            
            reportFileGenerated( log, outputFile, quiet )
        }
}

/**
  * Visual studio cl.exe compiler
  */
case class VSCompiler(
    override val toolPaths : Seq[File],
    override val defaultLibraryPaths : Seq[File],
    override val defaultIncludePaths : Seq[File],
    val compilerExe : File,
    val archiverExe : File,
    val linkerExe : File,
    override val ccDefaultFlags : Seq[String] = Seq(),
    override val cxxDefaultFlags : Seq[String] = Seq(),
    override val linkDefaultFlags : Seq[String] = Seq() ) extends Compiler
{
    def findHeaderDependencies( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".d") )
    { depFile =>

        val depCmd = Seq[String]( compilerExe.toString, "/c", "/showIncludes", sourceFile.toString ) ++ compilerFlags ++ includePaths.flatMap( ip => Seq("/I", ip.toString ) ) ++ systemIncludePaths.flatMap( ip => Seq("/I", ip.toString) )
        val depResult = runProcess( log, depCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

        // Strip off any trailing backslash characters from the output
        val prefix = "Note: including file:"
        val depFileLines = depResult.stdout.split("\n").filter( _.startsWith(prefix) ).map( _.drop( prefix.size ).trim )

        // Drop the first column and split on spaces to get all the files (potentially several per line )
        val allFiles = depFileLines.map( x => new File(x) )

        IO.write( depFile, allFiles.mkString("\n") )

        reportFileGenerated( log, depFile, quiet )
    }

    def ccCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".obj") )
    { outputFile =>

        val buildCmd = Seq[String]( compilerExe.toString, "/c", "/EHsc", "/Fo" + outputFile.toString, sourceFile.toString ) ++ compilerFlags ++ includePaths.flatMap( ip => Seq("/I", ip.toString) ) ++ systemIncludePaths.flatMap( ip => Seq("/I", ip.toString) )

        runProcess( log, buildCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

        reportFileGenerated( log, outputFile, quiet )
    }
    
    def cxxCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".obj") )
    { outputFile =>

        val buildCmd = Seq[String]( compilerExe.toString, "/c", "/EHsc", "/Fo" + outputFile.toString, sourceFile.toString ) ++ compilerFlags ++ includePaths.flatMap( ip => Seq("/I", ip.toString) ) ++ systemIncludePaths.flatMap( ip => Seq("/I", ip.toString) )

        runProcess( log, buildCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

        reportFileGenerated( log, outputFile, quiet )
    }

    def buildStaticLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / (libName + ".lib") )
        { outputFile =>

            val arCmd = Seq[String]( archiverExe.toString, "/OUT:" + outputFile.toString ) ++ objectFiles.map( _.toString )
            
            runProcess( log, arCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

            reportFileGenerated( log, outputFile, quiet )
        }

    def buildSharedLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".so") )
        { outputFile =>

            val cmd = Seq[String]( compilerExe.toString, "/DLL", "/OUT:" + outputFile.toString ) ++ objectFiles.map( _.toString )

            runProcess( log, cmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

            reportFileGenerated( log, outputFile, quiet )
        }

    def buildExecutable( log : Logger, buildDirectory : File, exeName : String, linkFlags : Seq[String], linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / exeName )
        { outputFile =>
            val linkCmd = Seq[String]( linkerExe.toString, "/OUT:" + outputFile.toString ) ++ inputFiles.map( _.toString ) ++ linkPaths.map( lp => "/LIBPATH:" + lp ) ++ linkLibraries.map( ll => "-l" + ll )

            runProcess( log, linkCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

            reportFileGenerated( log, outputFile, quiet )
        }
}


