package org.seacourt.build

import sbt._
import Keys._
import complete.{Parser, RichParser}
import complete.DefaultParsers._
import scala.collection.{mutable, immutable}

import sbt.std.{TaskStreams}

class FunctionWithResultPath( val resultPath : File, val fn : () => File )
{
    def apply() = fn()
    def runIfNotCached( stateCacheDir : File, inputDeps : Seq[File] ) =
    {
        val lazyBuild = FileFunction.cached( stateCacheDir / resultPath.toString , FilesInfo.lastModified, FilesInfo.exists ) 
        { _ =>
            Set( fn() )
        }
        lazyBuild(inputDeps.toSet)
        
        resultPath
    }
}

object FunctionWithResultPath
{
    def apply( resultPath : File )( fn : File => Unit ) =
    {
        new FunctionWithResultPath( resultPath, () => { fn(resultPath); resultPath } )
    }
}

trait Compiler
{
    def findHeaderDependencies( s : TaskStreams[_], buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File ) : FunctionWithResultPath
    def compileToObjectFile( s : TaskStreams[_], buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File ) : FunctionWithResultPath
    def buildStaticLibrary( s : TaskStreams[_], buildDirectory : File, libName : String, objectFiles : Seq[File] ) : FunctionWithResultPath
    def buildExecutable( s : TaskStreams[_], buildDirectory : File, exeName : String, linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File] ) : FunctionWithResultPath
}

trait BuildTypeTrait
{
    def name        : String
    def pathDirs    : Seq[String]
}

case class Environment( val conf : BuildTypeTrait, val compiler : Compiler )

case class GccCompiler(
    val compilerPath : File,
    val archiverPath : File,
    val linkerPath : File,
    val compileFlags : String = "",
    val linkFlags : String = "" ) extends Compiler
{
    def findHeaderDependencies( s : TaskStreams[_], buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".d") )
    { depFile =>
    
        val includePathArg = includePaths.map( ip => "-I " + ip ).mkString(" ")
        val systemIncludePathArg = systemIncludePaths.map( ip => "-isystem " + ip ).mkString(" ")
        val depCmd = "%s %s -M %s %s %s".format( compilerPath, compileFlags, includePathArg, systemIncludePathArg, sourceFile )
        s.log.info( "Executing: " + depCmd )
        val depResult = stringToProcess( depCmd ).lines
        
        // Strip off any trailing backslash characters from the output
        val depFileLines = depResult.map( _.replace( "\\", "" ) )
    
        // Drop the first column and split on spaces to get all the files (potentially several per line )
        val allFiles = depFileLines.flatMap( _.split(" ").drop(1) ).map( x => new File(x.trim) )
        
        IO.write( depFile, allFiles.mkString("\n") )
    }
    
    def compileToObjectFile( s : TaskStreams[_], buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".o") )
    { outputFile =>
    
        val includePathArg = includePaths.map( ip => "-I " + ip ).mkString(" ")
        val systemIncludePathArg = systemIncludePaths.map( ip => "-isystem " + ip ).mkString(" ")
        val buildCmd = "%s %s %s %s -c -o %s %s".format( compilerPath, compileFlags, includePathArg, systemIncludePathArg, outputFile, sourceFile )
                       
        s.log.info( "Executing: " + buildCmd )
        buildCmd !!
    }
    
    def buildStaticLibrary( s : TaskStreams[_], buildDirectory : File, libName : String, objectFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".a") )
        { outputFile =>
        
            val arCmd = "%s -c -r %s %s".format( archiverPath, outputFile, objectFiles.mkString(" ") )
            s.log.info( "Executing: " + arCmd )
            arCmd !!
        }
        
    def buildExecutable( s : TaskStreams[_], buildDirectory : File, exeName : String, linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / exeName )
        { outputFile =>
        
            val linkPathArg = linkPaths.map( lp => "-L " + lp ).mkString(" ")
            val libArgs = linkLibraries.map( ll => "-l" + ll ).mkString(" ")
            val linkCmd = "%s %s -o %s %s %s %s".format( linkerPath, linkFlags, outputFile, inputFiles.mkString(" "), linkPathArg, libArgs )
            s.log.info( "Executing: " + linkCmd )
            linkCmd !!
        }
}


abstract class NativeBuild extends Build
{
    private val projectsBuffer = mutable.ArrayBuffer[Project]()
    
    override def projects: Seq[Project] = projectsBuffer
    def registerProject( p : Project ) =
    {
        println( "Registering: " + p.id )
        projectsBuffer.append(p)
    }
    
    implicit val nbuild = this

    def configurations : Set[Environment]

    type Sett = Project.Setting[_]
    //val compilerExe = SettingKey[File]("compiler", "Path to compiler executable")
    //val archiveExe = SettingKey[File]("archive", "Path to archive executable")
    
    val compiler = TaskKey[Compiler]("native-compiler")
    val buildEnvironment = TaskKey[Option[Environment]]("build-environment")
    val rootBuildDirectory = TaskKey[File]("root-build-dir", "Build root directory (for the config, not the project)")
    val projectBuildDirectory = TaskKey[File]("project-build-dir", "Build directory for this config and project")
    val stateCacheDirectory = TaskKey[File]("state-cache-dir", "Build state cache directory")
    val projectDirectory = TaskKey[File]("project-dir", "Project directory")
    val sourceDirectories = TaskKey[Seq[File]]("source-dir", "Source directory")
    val includeDirectories = TaskKey[Seq[File]]("include-dirs", "Include directories")
    val systemIncludeDirectories = TaskKey[Seq[File]]("system-include-dirs", "System include directories")
    val linkDirectories = TaskKey[Seq[File]]("link-dirs", "Link directories")
    val nativeLibraries = TaskKey[Seq[String]]("native-libraries", "All native library dependencies for this project")
    val sourceFiles = TaskKey[Seq[File]]("source-files", "All source files for this project")
    val sourceFilesWithDeps = TaskKey[Map[File, Seq[File]]]("source-files-with-deps", "All source files for this project")
    val objectFiles = TaskKey[Seq[File]]("object-files", "All object files for this project" )
    val nativeExe = TaskKey[File]("native-exe", "Executable built by this project (if appropriate)" )
    val nativeRun = TaskKey[Unit]("native-run", "Perform a native run of this project" )
    val testProject = TaskKey[Project]("test-project", "The test sub-project for this project")
    val test = TaskKey[Unit]("test", "Run the test associated with this project" )
    
    val exportedLibs = TaskKey[Seq[File]]("exported-libs", "All libraries exported by this project" )
    val exportedLibDirectories = TaskKey[Seq[File]]("exported-lib-directories", "All library directories exported by this project" )
    val exportedIncludeDirectories = TaskKey[Seq[File]]("exported-include-directories", "All include directories exported by this project" )

    
    val envKey = AttributeKey[Environment]("envKey")
    
    val buildOptsParser = Space ~> configurations.map( x => token(x.conf.name) ).reduce(_ | _)
    
    def setBuildConfigCommand = Command("build-environment")(_ => buildOptsParser)
    {
        (state, envName) =>
   
        val envDict = configurations.map( x => (x.conf.name, x) ).toMap
        val env = envDict(envName)

        val updatedAttributes = state.attributes.put( envKey, env )
        
        state.copy( attributes=updatedAttributes )
    }
    
    class RichNativeProject( val p : Project )
    {
        def nativeDependsOn( others : Project* ) : Project =
        {
            others.foldLeft(p)
            { case (np, other) =>
                np.settings(
                    includeDirectories  <++= (exportedIncludeDirectories in other),
                    objectFiles         <++= (exportedLibs in other),
                    compile             <<=  compile.dependsOn(exportedLibs in other) )
            }
        }
        def register() : Project = 
        {
            registerProject(p)
            
            p
        }
    }
    
    implicit def toRichNativeProject( p : Project ) = new RichNativeProject(p)

    object NativeProject
    {
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            val defaultSettings = Seq(
                name                := _name,
                
                projectDirectory    <<= baseDirectory map { bd => (bd / _projectDirectory.toString) },
            
                commands            += setBuildConfigCommand,
                
                buildEnvironment    <<= state map
                { s =>
                    s.attributes.get( envKey )
                },
                
                target              <<= baseDirectory { _ / "target" / "native" },
                
                historyPath         <<= target { t => Some(t / ".history") },
                
                rootBuildDirectory  <<= (target, buildEnvironment) map
                { case (td, beo) =>
                
                    if ( beo.isEmpty ) sys.error( "Please set a build configuration using the build-environment command" )
                    val be = beo.get
                
                    val dir = be.conf.pathDirs.foldLeft( td )( _ / _ )
                    
                    IO.createDirectory(dir)
                    
                    dir
                },
                
                clean               <<= (rootBuildDirectory) map { rbd => IO.delete(rbd) },
                
                compiler            <<= (buildEnvironment) map { _.get.compiler },
                
                projectBuildDirectory <<= (rootBuildDirectory, name) map
                { case (rbd, n) =>
                
                    val dir = rbd / n
                    
                    IO.createDirectory(dir)
                    
                    dir
                },
                
                stateCacheDirectory <<= (projectBuildDirectory) map { _ / "state-cache"  },
                
                includeDirectories  <<= (projectDirectory) map { pd => Seq(pd / "interface", pd / "include") },
                
                systemIncludeDirectories := Seq(),
                
                linkDirectories     :=  Seq(),
                
                nativeLibraries     <<= (projectBuildDirectory) map { _ => Seq() },
                
                sourceDirectories  <<= (projectDirectory) map { pd => Seq(pd / "source") },
                
                sourceFiles         <<= (sourceDirectories) map { _.flatMap { sd => ((sd * "*.cpp").get ++ (sd * "*.c").get) } },
                
                sourceFilesWithDeps <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, sourceFiles, streams) map
                {
                    case (c, bd, scd, ids, sids, sfs, s) =>
                    
                    // Calculate dependencies
                    def findDependencies( sourceFile : File ) : Seq[File] =
                    {
                        val depGen = c.findHeaderDependencies( s, bd, ids, sids, sourceFile )
                        
                        depGen.runIfNotCached( scd, Seq(sourceFile) )
                        
                        IO.readLines(depGen.resultPath).map( file )
                    }
                    
                    sfs.par.map( sf => (sf, findDependencies(sf) ) ).seq.toMap
                },
                
                watchSources        <++= (sourceFilesWithDeps) map { sfd => sfd.toList.flatMap { case (sf, deps) => (sf +: deps.toList) } },
                
                objectFiles         <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, sourceFiles, sourceFilesWithDeps, streams) map
                { case (c, bd, scd, ids, sids, sfs, sfdeps, s) =>
                    
                    // Build each source file in turn as required
                    sfs.par.map
                    { sourceFile =>
                        
                        val dependencies = sfdeps(sourceFile) :+ sourceFile
                        
                        s.log.debug( "Dependencies for %s: %s".format( sourceFile, dependencies.mkString(";") ) )
                        
                        val blf = c.compileToObjectFile( s, bd, ids, sids, sourceFile )
                        
                        blf.runIfNotCached( scd, dependencies )
                    }.seq
                },
                
                test                        <<= (streams) map { s => s.log.info( "No tests defined for this project" ) },
                exportedLibs                <<= (streams) map { s => s.log.info( "No libraries exported by this project" ); Seq() },
                exportedLibDirectories      <<= (streams) map { s => s.log.info( "No library paths exported by this project" ); Seq() },
                exportedIncludeDirectories  <<= (streams) map { s => s.log.info( "No include directories exported by this project" ); Seq() },
                nativeExe                   <<= (streams) map { s => s.log.info( "No executable built by this project" ); file("") }
            )
            
            val p = Project( id=_name, base=file("./"), settings=defaultSettings ++ _settings )
            
            p
        }
    }

    object StaticLibrary
    {
        private def buildLib( _name : String, _projectDirectory : File, settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            val defaultSettings = Seq(
                exportedLibs <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, streams) map
                { case (c, projName, bd, scd, ofs, s) =>
                
                    val blf = c.buildStaticLibrary( s, bd, projName, ofs )
                    
                    Seq( blf.runIfNotCached( scd, ofs ) )
                },
                exportedIncludeDirectories  <<= (projectDirectory) map { pd => Seq(pd / "interface") },
                exportedLibDirectories      <<= exportedLibs map { _.map( _.getParentFile ).distinct },
                compile                     <<= exportedLibs map { nc => sbt.inc.Analysis.Empty }
                
                
            )
            NativeProject( _name, _projectDirectory, defaultSettings ++ settings )
        }
        
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            val mainLibrary = buildLib( _name, _projectDirectory, _settings )
                    
            
            val testDir = (_projectDirectory / "test")
            if ( testDir.exists )
            {
                val testName = _name + "_test"
                
                NativeTest( testName, testDir, Seq
                (
                    includeDirectories  <++= (includeDirectories in mainLibrary),
                    objectFiles         <++= (exportedLibs in mainLibrary)
                ) ++ _settings ) 
            }
            
            mainLibrary
        }
    }
    
    object NativeTest
    {
        def apply( _name : String, _projectDirectory : File, settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            val defaultSettings = Seq(
                nativeExe <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, linkDirectories, nativeLibraries, streams) map
                { case (c, projName, bd, scd, ofs, lds, nls, s) =>
                
                    val blf = c.buildExecutable( s, bd, projName, lds, nls, ofs )
                    
                    blf.runIfNotCached( scd, ofs )
                },
                compile <<= nativeExe map { nc => sbt.inc.Analysis.Empty },
                test <<= nativeExe map { ncExe =>
                    val res = ncExe.toString !
                
                    if ( res != 0 ) sys.error( "Non-zero exit code: " + res.toString )
                }
            )
            NativeProject( _name, _projectDirectory, defaultSettings ++ settings )
        }
    }

    object NativeExecutable
    {
        def apply( _name : String, _projectDirectory : File, settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            val defaultSettings = Seq(
                nativeExe <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, linkDirectories, nativeLibraries, streams) map
                { case (c, projName, bd, scd, ofs, lds, nls, s) =>
                
                    val blf = c.buildExecutable( s, bd, projName, lds, nls, ofs )
                    
                    blf.runIfNotCached( scd, ofs )
                },
                compile <<= nativeExe map { nc => sbt.inc.Analysis.Empty },
                run <<= inputTask { (argTask: TaskKey[Seq[String]]) =>
                    
                    (argTask, nativeExe, streams) map
                    { case (args, nbExe, s) =>
                    
                        val res = (nbExe.toString + " " + args.mkString(" ")) !
                    
                        if ( res != 0 ) sys.error( "Non-zero exit code: " + res.toString )
                    }
                }
            )
            NativeProject( _name, _projectDirectory, defaultSettings ++ settings )
        }
    }
    
    
}


