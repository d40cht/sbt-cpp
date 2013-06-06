package org.seacourt.build

import sbt._
import Keys._
import complete.{Parser, RichParser}
import complete.DefaultParsers._
import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import scala.collection.{mutable, immutable}

import com.typesafe.config.{Config}
import scala.collection.JavaConversions._

/**
  * The base trait from which all native compilers must be inherited in order
  */
trait Compiler
{
    def toolPaths           : Seq[File]
    def defaultLibraryPaths : Seq[File]
    def defaultIncludePaths : Seq[File]
    def ccExe               : File
    def cxxExe              : File
    def archiverExe         : File
    def linkerExe           : File
    def ccDefaultFlags      : Seq[String]
    def cxxDefaultFlags     : Seq[String]
    def linkDefaultFlags    : Seq[String]
    
    def findHeaderDependencies( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean = false ) : FunctionWithResultPath
    
    def ccCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean = false ) : FunctionWithResultPath
    def cxxCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean = false ) : FunctionWithResultPath

    def buildStaticLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], linkFlags : Seq[String], quiet : Boolean = false ) : FunctionWithResultPath
    def buildSharedLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], linkPaths : Seq[File], linkLibraries : Seq[String], linkFlags : Seq[String],  quiet : Boolean = false ) : FunctionWithResultPath
    def buildExecutable( log : Logger, buildDirectory : File, exeName : String, linkFlags : Seq[String], linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File], quiet : Boolean = false ) : FunctionWithResultPath
}

trait CompilationProcess
{
    protected def reportFileGenerated( log : Logger, genFile : File, quiet : Boolean )
    {
        if ( !quiet ) log.info( genFile.toString )
    }
    
    case class ProcessResult( val retCode : Int, val stdout : String, val stderr : String )
    
    protected def runProcess( log : Logger, cmd : Seq[String], cwd : File, env : Seq[(String, String)], quiet : Boolean ) =
    {
        val pl = new ProcessOutputToString(true)
        
        log.debug( "Executing: " + cmd.mkString(" ") )
        val res = Process( cmd, cwd, env : _* ) ! pl
        
        if ( quiet )
        {
            pl.stdout.foreach( ll => log.debug(ll) )
        }
        else
        {
            if ( res == 0 )
            {
                pl.stdout.foreach( ll => log.warn(ll) )
            }
            else
            {
                pl.stdout.foreach( ll => log.error(ll) )
            }
            
        }
        
        if ( res != 0 ) throw new java.lang.RuntimeException( "Non-zero exit code: " + res )
        
        new ProcessResult(res, pl.stdout.mkString("\n"), pl.stderr.mkString("\n"))
    }
}

trait CompilerWithConfig extends Compiler
{
    def buildTypeTrait      : BuildTypeTrait
    def config              : Config

    private val configPrefix = buildTypeTrait.pathDirs
    private def ton( d : Seq[String] ) = d.mkString(".")
    
    override def toolPaths           = config.getStringList( ton( configPrefix :+ "toolPaths" ) ).map( file )
    override def defaultIncludePaths = config.getStringList( ton( configPrefix :+ "includePaths" ) ).map( file )
    override def defaultLibraryPaths = config.getStringList( ton( configPrefix :+ "libraryPaths" ) ).map( file )
    override def ccExe               = file( config.getString( ton( configPrefix :+ "ccExe" ) ) )
    override def cxxExe              = file( config.getString( ton( configPrefix :+ "cxxExe" ) ) )
    override def archiverExe         = file( config.getString( ton( configPrefix :+ "archiver" ) ) )
    override def linkerExe           = file( config.getString( ton( configPrefix :+ "linker" ) ) )
    override def ccDefaultFlags      = config.getStringList( ton( configPrefix :+ "ccFlags" ) )
    override def cxxDefaultFlags     = config.getStringList( ton( configPrefix :+ "cxxFlags" ) )
    override def linkDefaultFlags    = config.getStringList( ton( configPrefix :+ "linkFlags" ) )
}

/*case class NativeAnalysis[T]( val data : T, val warningLines : Seq[String] = Seq() )
{
    def addWarningLine( line : String ) = new NativeAnalysis( data, line +: warningLines )
}*/



/**
  * Build configurations for a particular project must inherit from this trait.
  * See the default in NativeDefaultBuild for more details
  */
trait BuildTypeTrait
{
    def name            : String
    def pathDirs        : Seq[String]
    
    def isCrossCompile  = false
    
    def targetDirectory( rootDirectory : File ) = pathDirs.foldLeft( rootDirectory )( _ / _ )
}

/**
  * Keys for a native build that should be visible from all types of sbt project (including scala)
  */
object NativeBuild
{    
    val exportedLibs = TaskKey[Seq[File]]("native-exported-libs", "All libraries exported by this project" )
    val exportedLibDirectories = TaskKey[Seq[File]]("native-exported-lib-directories", "All library directories exported by this project" )
    val exportedIncludeDirectories = TaskKey[Seq[File]]("native-exported-include-directories", "All include directories exported by this project" )
}

/**
  * The base mechanics, keys and build graph for a native build.
  * The possible build configurations remain abstract via BuildType and
  * the configurations Set. These need to be provided in a derived class.
  */

abstract class NativeBuild extends Build
{
    import NativeBuild._
    
    //type ObjectFile = NativeAnalysis[File]
    
    // Magic Gav suggested that the ConfigFactory needs the classloader for this class
    // in order to be able to get things out of the jar in which it is packaged. This seems
    // to work, so kudos to him.
    lazy val parseOptions = ConfigParseOptions.defaults().setAllowMissing(true)
    lazy val defaultConf = ConfigFactory.load(getClass.getClassLoader)
    lazy val localConf = ConfigFactory.parseFile( file("build.conf").getAbsoluteFile, parseOptions )
    lazy val userConf = ConfigFactory.parseFile( file("user.conf").getAbsoluteFile, parseOptions )
    
    lazy val conf = userConf.withFallback( localConf ).withFallback( defaultConf ).resolve()
    
    lazy val ccFilePattern = Seq("*.c")
    lazy val cxxFilePattern = Seq("*.cpp", "*.cxx")
    
    lazy val buildRootDirectory = file( conf.getString( "build.rootdirectory" ) ).getAbsoluteFile
    
    private lazy val allProjectVals : Seq[Project] = ReflectUtilities.allVals[Project](this).values.toSeq
    
    /**
      * This is the general entry point for sbt. So we do some checking here too
      */
    override lazy val projects : Seq[Project] =
    {
        val allProjects = allProjectVals
        val allProjectRefs = allProjects.map( p => LocalProject(p.id) )
        
        // If there isn't an 'all' project, make one
        if ( allProjects.map( _.id ) contains "all" )
        {
            allProjects
        }
        else
        {
            NativeProject("all", file("."), NativeProject.baseSettings).aggregate( allProjectRefs : _* ) +: allProjects
        }
    }

    type BuildType <: BuildTypeTrait
    
    case class BuildConfiguration( val conf : BuildType, val compiler : Compiler )
    
    def configurations : Set[BuildConfiguration]
    
    /**
     * Override this in your project to do appropriate checks on the build environment
     */
    def checkConfiguration( log : Logger, env : BuildConfiguration ) = { }
    val compiler = TaskKey[Compiler]("native-compiler")
    val buildConfiguration = TaskKey[BuildConfiguration]("native-build-configuration-key")
    val rootBuildDirectory = TaskKey[File]("native-root-build-dir", "Build root directory (for the config, not the project)")
    val projectBuildDirectory = TaskKey[File]("native-project-build-dir", "Build directory for this config and project")
    val stateCacheDirectory = TaskKey[File]("native-state-cache-dir", "Build state cache directory")
    val projectDirectory = TaskKey[File]("native-project-dir", "Project directory")
    val sourceDirectories = TaskKey[Seq[File]]("native-source-dirs", "Source directories")
    val includeDirectories = TaskKey[Seq[File]]("native-include-dirs", "Include directories")
    val systemIncludeDirectories = TaskKey[Seq[File]]("native-system-include-dirs", "System include directories")
    val linkDirectories = TaskKey[Seq[File]]("native-link-dirs", "Link directories")
    val nativeLibraries = TaskKey[Seq[String]]("native-libraries", "All native library dependencies for this project")
    val ccSourceFiles = TaskKey[Seq[File]]("native-cc-source-files", "All C source files for this project")
    val cxxSourceFiles = TaskKey[Seq[File]]("native-cxx-source-files", "All C++ source files for this project")
    val ccSourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("native-cc-source-files-with-deps", "All C source files with dependencies for this project")
    val cxxSourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("native-cxx-source-files-with-deps", "All C++ source files with dependencies for this project")
    val objectFiles = TaskKey[Seq[File]]("native-object-files", "All object files for this project" )
    val archiveFiles = TaskKey[Seq[File]]("native-archive-files", "All archive files for this project, specified by full path" )
    val nativeExe = TaskKey[File]("native-exe", "Executable built by this project (if appropriate)" )
    val testExe = TaskKey[Option[File]]("native-test-exe", "Test executable built by this project (if appropriate)" )
    val nativeRun = TaskKey[Unit]("native-run", "Perform a native run of this project" )
    val testProject = TaskKey[Project]("native-test-project", "The test sub-project for this project")
    val testExtraDependencies = TaskKey[Seq[File]]("native-test-extra-dependencies", "Extra file dependencies of the test (used to calculate when to re-run tests)")
    val nativeTest = TaskKey[Option[(File, File)]]("native-test-run", "Run the native test, returning the files with stdout and stderr respectively")
    val test = TaskKey[Unit]("test", "Run the test associated with this project")
    val environmentVariables = TaskKey[Seq[(String, String)]]("native-env-vars", "Environment variables to be set for running programs and tests")
    val cleanAll = TaskKey[Unit]("native-clean-all", "Clean the entire build directory")
    val ccCompileFlags = TaskKey[Seq[String]]("native-cc-flags", "Native C compile flags")
    val cxxCompileFlags = TaskKey[Seq[String]]("native-cxx-flags", "Native C++ compile flags")
    val linkFlags = TaskKey[Seq[String]]("native-link-flags", "Native link flags")
    

    type Sett = Project.Setting[_]
    
    val buildOptsParser = Space ~> configurations.map( x => token(x.conf.name) ).reduce(_ | _)
    
    
    val nativeBuildConfigurationCommandName = "native-build-configuration"
    
    val configKey = AttributeKey[BuildConfiguration]("configKey")
    
    def setBuildConfigCommand = Command(nativeBuildConfigurationCommandName)(_ => buildOptsParser)
    {
        (state, configName) =>
   
        val configDict = configurations.map( x => (x.conf.name, x) ).toMap
        val config = configDict(configName)

        val updatedAttributes = state.attributes.put( configKey, config )
        
        state.copy( attributes=updatedAttributes )
    }
    
    
    override def settings = super.settings ++ Seq(
        commands                    ++= (BasicCommands.allBasicCommands :+ setBuildConfigCommand),
        
        buildConfiguration          <<= state map
        { s =>
            val beo = s.attributes.get( configKey )
            
            if ( beo.isEmpty ) sys.error( "Please set a build configuration using the %s command".format(nativeBuildConfigurationCommandName) )
            
            val config = beo.get
            val configCheckFile = config.conf.targetDirectory(buildRootDirectory) / "EnvHealthy.txt"
            
            if ( !configCheckFile.exists )
            {
                checkConfiguration( s.log, config )
                IO.write( configCheckFile, "HEALTHY" )
            }
            
            beo.get
        },
        
        shellPrompt :=
        { state =>
            val projectId = Project.extract(state).currentProject.id
            val config = state.attributes.get( configKey )
            "%s|%s:> ".format(config.map { _.conf.name }.getOrElse("No-config"), projectId )
        }
    )
    
    case class RichNativeProject( val p : Project )
    {
        def nativeDependsOn( others : ProjectReference* ) : Project =
        {
            others.foldLeft(p)
            { case (np, other) =>
                np.dependsOn( other ).settings(
                    includeDirectories in Compile   <++= (exportedIncludeDirectories in other),
                    linkDirectories in Compile      <++= (exportedLibDirectories in other),
                    archiveFiles in Compile         <++= (exportedLibs in other) )
            }
        }
        
        def nativeSystemDependsOn( others : ProjectReference* ) : Project =
        {
            others.foldLeft(p)
            { case (np, other) =>
                np.dependsOn( other ).settings(
                    systemIncludeDirectories in Compile <++= (exportedIncludeDirectories in other),
                    linkDirectories in Compile          <++= (exportedLibDirectories in other),
                    archiveFiles in Compile             <++= (exportedLibs in other) )
            }
        }
    }
    
    implicit def toRichNativeProject( p : Project ) = new RichNativeProject(p)
    
    
    object NativeProject
    {
        // A selection of useful default settings from the standard sbt config
        lazy val relevantSbtDefaultSettings = Seq[Sett](
            watchTransitiveSources          <<= Defaults.watchTransitiveSourcesTask,                
            watch                           <<= Defaults.watchSetting
        )
        
    
        lazy val configSettings = Seq(
            target                      := buildRootDirectory,
            
            historyPath                 <<= target { t => Some(t / ".history") },
            
            rootBuildDirectory          <<= (target, buildConfiguration) map { case (td, be) => be.conf.targetDirectory(td) },
            
            clean                       <<= (rootBuildDirectory) map { rbd => IO.delete(rbd) },
            
            cleanAll                    <<= (target) map { td => IO.delete(td) },
            
            compiler                    <<= (buildConfiguration) map { _.compiler },
            
            projectBuildDirectory       <<= (rootBuildDirectory, name) map
            { case (rbd, n) =>
            
                val dir = rbd / n
                
                IO.createDirectory(dir)
                
                dir
            },
            
            stateCacheDirectory         <<= (projectBuildDirectory) map { _ / "state-cache"  },
            
            systemIncludeDirectories    <<= (compiler) map { _.defaultIncludePaths },
            
            nativeTest                  :=  None,
            
            exportedLibs                := Seq(),
            exportedLibDirectories      := Seq(),
            exportedIncludeDirectories  := Seq(),
            nativeExe                   := file("")
            
        )
        
        def buildSettings = Seq(
            
            ccSourceFiles               <<= (sourceDirectories) map { _.flatMap
            { sd =>
                ccFilePattern.flatMap( fp => (sd * fp).get )
            } },
            
            cxxSourceFiles              <<= (sourceDirectories) map { _.flatMap
            { sd =>
                cxxFilePattern.flatMap( fp => (sd * fp).get )
            } },
            
            ccCompileFlags              <<= (compiler) map { _.ccDefaultFlags },
            
            cxxCompileFlags             <<= (compiler) map { _.cxxDefaultFlags },
            
            linkDirectories             <<= (compiler) map { _.defaultLibraryPaths },

            nativeLibraries             <<= (projectBuildDirectory) map { _ => Seq() },
            
            archiveFiles                <<= (projectBuildDirectory) map { _ => Seq() },
            
            linkFlags                   <<= (compiler) map { _.linkDefaultFlags },
            
            ccSourceFilesWithDeps       <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, ccSourceFiles, ccCompileFlags, streams) flatMap
            {
                case (c, bd, scd, ids, sids, sfs, cfs, s) =>
                
                // Calculate dependencies
                def findDependencies( sourceFile : File ) : Seq[File] =
                {
                    val depGen = c.findHeaderDependencies( s.log, bd, ids, sids, sourceFile, cfs )
                    
                    depGen.runIfNotCached( scd, Seq(sourceFile) )
                    
                    IO.readLines(depGen.resultPath).map( file )
                }
                
                sfs.map( sf => toTask( () => (sf, findDependencies(sf)) ) ).join
            },
            
            cxxSourceFilesWithDeps      <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, cxxSourceFiles, cxxCompileFlags, streams) flatMap
            {
                case (c, bd, scd, ids, sids, sfs, cfs, s) =>
                
                // Calculate dependencies
                def findDependencies( sourceFile : File ) : Seq[File] =
                {
                    val depGen = c.findHeaderDependencies( s.log, bd, ids, sids, sourceFile, cfs )
                    
                    depGen.runIfNotCached( scd, Seq(sourceFile) )
                    
                    IO.readLines(depGen.resultPath).map( file )
                }
                
                sfs.map( sf => toTask( () => (sf, findDependencies(sf)) ) ).join
            },
            
            environmentVariables        := Seq(),
            
            objectFiles                 <<= (compiler, projectBuildDirectory, stateCacheDirectory, includeDirectories, systemIncludeDirectories, ccSourceFilesWithDeps, cxxSourceFilesWithDeps, ccCompileFlags, cxxCompileFlags, streams) flatMap
            {
                
                case (c, bd, scd, ids, sids, ccfs, cxxfs, ccflags, cxxflags, s) =>
                
                val ccTasks = ccfs.map
                {
                    case (sourceFile, dependencies) =>
                
                    val blf = c.ccCompileToObj( s.log, bd, ids, sids, sourceFile, ccflags )
                            
                    toTask( () => blf.runIfNotCached( scd, sourceFile +: dependencies ) )
                }
                
                val cxxTasks = cxxfs.map
                {
                    case (sourceFile, dependencies) =>
                
                    val blf = c.cxxCompileToObj( s.log, bd, ids, sids, sourceFile, cxxflags )
                            
                    toTask( () => blf.runIfNotCached( scd, sourceFile +: dependencies ) )
                }
                
                (ccTasks ++ cxxTasks).join
            }            
        )
        
        def compileSettings = inConfig(Compile)( buildSettings ++ Seq[Sett](
            includeDirectories          <<= (projectDirectory) map { pd => Seq(pd / "interface", pd / "include") },
            sourceDirectories           <<= (projectDirectory) map { pd => Seq(pd / "source") }
        ) )
        
        def testSettings = inConfig(Test)( buildSettings ++ Seq[Sett](
            projectDirectory            <<= (projectDirectory in Compile) map { pd => pd / "test" },
            includeDirectories          <<= (projectDirectory) map { pd => Seq(pd / "include") },
            includeDirectories          <++= (includeDirectories in Compile),
            includeDirectories          <++= (exportedIncludeDirectories in Compile),
            linkDirectories             <++= (linkDirectories in Compile),
            archiveFiles                <++= (archiveFiles in Compile),
            sourceDirectories           <<= (projectDirectory) map { pd => Seq(pd / "source") },
            
            testExe                     <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, exportedLibs in Compile, archiveFiles, linkFlags, linkDirectories, nativeLibraries, streams) map
            { case (c, projName, bd, scd, ofs, pls, afs, lfs, lds, nls, s) =>
            
                if ( ofs.isEmpty )
                {
                    s.log.info( "No tests defined for: " + projName )
                    None
                }
                else
                {
                    val blf = c.buildExecutable( s.log, bd, projName + "_test", lfs, lds, nls, ofs ++ pls ++ afs )
                    
                    Some( blf.runIfNotCached( scd, ofs ++ pls ++ afs ) )
                }                    
            },
            
            testExtraDependencies       <<= (projectDirectory) map { pd => ((pd / "data") ** "*").get },

            nativeTest                  <<= (testExe, testExtraDependencies, environmentVariables in Test, stateCacheDirectory, projectDirectory, buildConfiguration, streams) map
            {
                case (Some(tExe), teds, tenvs, scd, pd, bc, s) if !bc.conf.isCrossCompile =>
                {
                    val resFile = file( tExe + ".res" )
                    val stdoutFile = file( tExe + ".stdout" )
                    
                    // TODO: This mutable var is evil. Return res more sanely from the closure
                    var res = 0
                    val tcf = FunctionWithResultPath( stdoutFile )
                    { _ =>
                        s.log.info( "Running test: " + tExe )
                        
                        val pb = Process( tExe.toString :: Nil, pd, tenvs : _* )
                        val po = new ProcessOutputToString( mergeToStdout=true )
                        res = pb ! po
                        
                        IO.writeLines( stdoutFile, po.stdout )
                        IO.writeLines( resFile, Seq( res.toString ) )
                        
                    }
                    
                    tcf.runIfNotCached( scd, tExe +: teds )
                    
                    Some( (resFile, stdoutFile) )
                }
                case _ => None                    
            },
            
            compile                     <<= (testExe) map { nc => sbt.inc.Analysis.Empty },
            
            test                        <<= (nativeTest, streams, name).map
            {
                case (Some( (resFile, stdOutFile) ), s, n) =>
                {
                    val res = IO.readLines(resFile).head.toInt
                    if ( res != 0 )
                    {
                        s.log.error( "Test failed: " + n )
                        IO.readLines( stdOutFile ).foreach( l => s.log.info( l ) )
                        sys.error( "Non-zero exit code: " + res.toString )
                    }
                }
                case (None, s, n) =>
                  
            }
        ) )
        
        lazy val baseSettings =
            relevantSbtDefaultSettings ++
            configSettings ++
            inConfig(Compile)(compileSettings) ++ Seq(
                watchSources                <++= (ccSourceFilesWithDeps in Compile, cxxSourceFilesWithDeps in Compile) map
                { (ccsfd, cxxsfd) =>
                
                    (ccsfd ++ cxxsfd).flatMap { case (sf, deps) => (sf +: deps.toList) }.toList.distinct
                },
                watchSources                <++= (ccSourceFilesWithDeps in Test, cxxSourceFilesWithDeps in Test) map
                { (ccsfd, cxxsfd) =>
                
                    (ccsfd ++ cxxsfd).flatMap { case (sf, deps) => (sf +: deps.toList) }.toList.distinct
                }
            )
            
        lazy val staticLibrarySettings = baseSettings ++ Seq(
            exportedLibs <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles in Compile, linkFlags in Compile, streams) map
            { case (c, projName, bd, scd, ofs, lfs, s) =>
            
                val blf = c.buildStaticLibrary( s.log, bd, projName, ofs, lfs )
                
                Seq( blf.runIfNotCached( scd, ofs ) )
            },
            exportedIncludeDirectories  <<= (projectDirectory in Compile) map { pd => Seq(pd / "interface") },
            exportedLibDirectories      <<= exportedLibs map { _.map( _.getParentFile ).distinct },
            compile in Compile          <<= exportedLibs map { nc => sbt.inc.Analysis.Empty }
        ) ++ testSettings
        
        lazy val sharedLibrarySettings = baseSettings ++ Seq(
            exportedLibs <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles in Compile, archiveFiles in Compile, linkDirectories in Compile, nativeLibraries in Compile, linkFlags in Compile, streams) map
            { case (c, projName, bd, scd, ofs, ars, lds, nls, lfs, s) =>
            
                val blf = c.buildSharedLibrary( s.log, bd, projName, ofs ++ ars, lds, nls, lfs )
                
                Seq( blf.runIfNotCached( scd, ofs ) )
            },
            exportedIncludeDirectories  <<= (projectDirectory in Compile) map { pd => Seq(pd / "interface") },
            exportedLibDirectories      <<= exportedLibs map { _.map( _.getParentFile ).distinct },
            compile in Compile          <<= exportedLibs map { nc => sbt.inc.Analysis.Empty }
        )++ testSettings
        
        lazy val nativeExeSettings = baseSettings ++ inConfig(Compile)( Seq(
            nativeExe in Compile <<= (compiler, name, projectBuildDirectory, stateCacheDirectory, objectFiles, archiveFiles, linkFlags, linkDirectories, nativeLibraries, streams) map
            { case (c, projName, bd, scd, ofs, afs, lfs, lds, nls, s) =>
            
                val blf = c.buildExecutable( s.log, bd, projName, lfs, lds, nls, ofs ++ afs )
                
                blf.runIfNotCached( scd, ofs ++ afs )
            },
            compile in Compile <<= nativeExe map { nc => sbt.inc.Analysis.Empty },
            run <<= inputTask { (argTask: TaskKey[Seq[String]]) =>
                
                (argTask, environmentVariables, nativeExe, projectDirectory, streams) map
                { case (args, renvs, ncExe, pd, s) =>
                
                    val res = Process( ncExe.toString +: args, pd, renvs : _* ) !
                
                    if ( res != 0 ) sys.error( "Non-zero exit code: " + res.toString )
                }
            }
        ) )
            
        def apply( _name : String, _projectDirectory : File, _settings : => Seq[sbt.Project.Setting[_]] ) =
        {
            Project( id=_name, base=_projectDirectory, settings=Seq(
                name                        := _name,        
                projectDirectory in Compile <<= baseDirectory map { bd => (bd / _projectDirectory.toString) } ) ++ _settings )
        }
    }
}



