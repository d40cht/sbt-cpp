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

case class Environment( val name : String, val compiler : Compiler )

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
    val buildEnvironment = SettingKey[Option[Environment]]("build-environment")
    val rootBuildDirectory = TaskKey[File]("root-build-dir", "Build root directory (for the config, not the project)")
    val projectBuildDirectory = TaskKey[File]("project-build-dir", "Build directory for this config and project")
    val stateCacheDirectory = TaskKey[File]("state-cache-dir", "Build state cache directory")
    val projectDirectory = SettingKey[File]("project-dir", "Project directory")
    val sourceDirectory = TaskKey[File]("source-dir", "Source directory")
    val includeDirectories = TaskKey[Seq[File]]("include-dirs", "Include directories")
    val systemIncludeDirectories = TaskKey[Seq[File]]("system-include-dirs", "System include directories")
    val linkDirectories = TaskKey[Seq[File]]("link-dirs", "Link directories")
    val nativeLibraries = TaskKey[Seq[String]]("native-libraries", "All native library dependencies for this project")
    val sourceFiles = TaskKey[Seq[File]]("source-files", "All source files for this project")
    val sourceFilesWithDeps = TaskKey[Map[File, Seq[File]]]("source-files-with-deps", "All source files for this project")
    val objectFiles = TaskKey[Seq[File]]("object-files", "All object files for this project" )
    val nativeCompile = TaskKey[File]("native-compile", "Perform a native compilation for this project" )
    val nativeRun = TaskKey[Unit]("native-run", "Perform a native run of this project" )
    val testProject = TaskKey[Project]("test-project", "The test sub-project for this project")
    
    val buildOptsParser = Space ~> configurations.map( x => token(x.name) ).reduce(_ | _)
    
    def setBuildConfigCommand = Command("build-environment")(_ => buildOptsParser)
    {
        (state, envName) =>
   
        val envDict = configurations.map( x => (x.name, x) ).toMap
        val env = envDict(envName)
        
        val extracted : Extracted = Project.extract(state)
        
        // Reconfigure all projects to this new build config
        val buildEnvironmentUpdateCommands = extracted.structure.allProjectRefs.map
        { pref =>
        
            (buildEnvironment in pref) := Some(env)
        }
        
        extracted.append( buildEnvironmentUpdateCommands, state )
    }
   
    def buildCommand = Command.command("build")
    { state =>
   
        // TODO: This is NOT HOW IT SHOULD BE. We need to get SBT to use its
        // own internal dependency analysis mechanism here. Yuk yuk yuk.
        val extracted : Extracted = Project.extract(state)
        
        var seenProjectIds = Set[String]()
        def buildProjectRecursively( project : ResolvedProject, projectRef : ProjectRef )
        {
            def scheduleProjectRef( projectReference : ProjectRef ) =
            {
                val resolvedO = Project.getProjectForReference( projectReference, extracted.structure )
                
                
                resolvedO.foreach
                { r =>
                    if ( !seenProjectIds.contains( r.id ) )
                    {
                        seenProjectIds += r.id
                        buildProjectRecursively( r, projectReference )
                    }
                }
            }
            
            project.dependencies.foreach { cpd => scheduleProjectRef( cpd.project ) }
            project.aggregate.foreach { agg => scheduleProjectRef( agg ) }
            
            Project.runTask(
                // The task to run
                nativeCompile in projectRef,
                state,
                // Check for cycles
                true )
        }
        
        buildProjectRecursively( extracted.currentProject, extracted.currentRef )
            
        state
    }

    object NativeProject
    {
         def apply(
            id: String,
            base: File,
            aggregate : => Seq[ProjectReference] = Nil,
            dependencies : => Seq[ClasspathDep[ProjectReference]] = Nil,
            delegates : => Seq[ProjectReference] = Nil,
            settings : => Seq[sbt.Project.Setting[_]] = Nil,
            configurations : Seq[Configuration] = Configurations.default )( implicit nb : NativeBuild ) =
        {
            val defaultSettings = Seq(
                name                := id,
            
                commands            ++= Seq( buildCommand, setBuildConfigCommand ),
                
                buildEnvironment    := None,
                
                rootBuildDirectory  <<= (baseDirectory, buildEnvironment) map
                { case (bd, beo) =>
                
                    if ( beo.isEmpty ) sys.error( "Please set a build configuration using the build-environment command" )
                    val be = beo.get
                
                    val dir = bd / "sbtbuild" / be.name
                    
                    IO.createDirectory(dir)
                    
                    dir
                },
                
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
                
                sourceDirectory     <<= (projectDirectory) map { _ / "source" },
                
                sourceFiles         <<= (sourceDirectory) map { pd => ((pd ** "*.cpp").get ++ (pd ** "*.c").get) },
                
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
                    
                    sfs.map( sf => (sf, findDependencies(sf) ) ).toMap
                },
                
                watchSources        <++= (sourceFilesWithDeps) map { sfd => sfd.toList.flatMap { case (sf, deps) => (sf +: deps.toList) } },
                
                objectFiles         <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, sourceFiles, sourceFilesWithDeps, streams) map
                { case (c, bd, scd, ids, sids, sfs, sfdeps, s) =>
                    
                    // Build each source file in turn as required
                    sfs.map
                    { sourceFile =>
                        
                        val dependencies = sfdeps(sourceFile) :+ sourceFile
                        
                        s.log.debug( "Dependencies for %s: %s".format( sourceFile, dependencies.mkString(";") ) )
                        
                        val blf = c.compileToObjectFile( s, bd, ids, sids, sourceFile )
                        
                        blf.runIfNotCached( scd, dependencies )
                    }
                }
            )
            
            val p = Project( id, base, aggregate, dependencies, delegates, defaultSettings ++ settings, configurations )
            nb.registerProject(p)
            
            p
        }
    }


    object StaticLibrary
    {
        def apply(
            id: String,
            base: File,
            aggregate : => Seq[ProjectReference] = Nil,
            dependencies : => Seq[ClasspathDep[ProjectReference]] = Nil,
            delegates : => Seq[ProjectReference] = Nil,
            settings : => Seq[sbt.Project.Setting[_]],
            configurations : Seq[Configuration] = Configurations.default ) =
        {
            val defaultSettings = Seq(
                nativeCompile <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, streams) map
                { case (c, projName, bd, scd, ofs, s) =>
                
                    val blf = c.buildStaticLibrary( s, bd, projName, ofs )
                    
                    blf.runIfNotCached( scd, ofs )
                },
                //(compile in Compile) <<= (compile in Compile) dependsOn (nativeCompile)
                compile <<= nativeCompile map { nc => sbt.inc.Analysis.Empty }
                
                
            )
            NativeProject( id, base, aggregate, dependencies, delegates, defaultSettings ++ settings, configurations )
        }
    }

    object NativeExecutable
    {
        def apply(
            id: String,
            base: File,
            aggregate : => Seq[ProjectReference] = Nil,
            dependencies : => Seq[ClasspathDep[ProjectReference]] = Nil,
            delegates : => Seq[ProjectReference] = Nil,
            settings : => Seq[sbt.Project.Setting[_]] = Defaults.defaultSettings,
            configurations : Seq[Configuration] = Configurations.default ) =
        {
            val defaultSettings = Seq(
                nativeCompile <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, linkDirectories, nativeLibraries, streams) map
                { case (c, projName, bd, scd, ofs, lds, nls, s) =>
                
                    val blf = c.buildExecutable( s, bd, projName, lds, nls, ofs )
                    
                    blf.runIfNotCached( scd, ofs )
                },
                compile <<= nativeCompile map { nc => sbt.inc.Analysis.Empty },
                run <<= inputTask { (argTask: TaskKey[Seq[String]]) =>
                    
                    (argTask, nativeCompile, streams) map
                    { case (args, nbExe, s) =>
                    
                        val res = (nbExe.toString + " " + args.mkString(" ")) !
                    
                        if ( res != 0 ) sys.error( "Non-zero exit code: " + res.toString )
                    }
                }
            )
            NativeProject( id, base, aggregate, dependencies, delegates, defaultSettings ++ settings, configurations )
        }
    }

    object NativeExecutable2
    {
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            NativeExecutable( id=_name, base=file("./"), settings=Seq( projectDirectory := _projectDirectory ) ++ _settings )
        }
    }

    object StaticLibrary2
    {
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            val mainLibrary = StaticLibrary( id=_name, base=file("./"), settings=Seq( projectDirectory := _projectDirectory ) ++ _settings )
                    
            
            val testDir = (_projectDirectory / "test")
            if ( testDir.exists )
            {
                val testName = _name + "_test"
                
                NativeExecutable2( testName, testDir, Seq
                (
                    includeDirectories  <++= (includeDirectories in mainLibrary),
                    objectFiles         <+= (nativeCompile in mainLibrary)
                ) ++ _settings ) 
            }
            
            mainLibrary
        }
    }
}


