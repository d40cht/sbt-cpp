package org.seacourt.build

import sbt._
import Keys._
import complete.{ Parser, RichParser }
import complete.DefaultParsers._
import com.typesafe.config.{ ConfigFactory, ConfigParseOptions }
import scala.collection.{ mutable, immutable }

import com.typesafe.config.{ Config }
import scala.collection.JavaConversions._

/**
 * The base trait from which all native compilers must be inherited in order
 */
trait Compiler {
  /**
   * TODO COMMENT: What are "tools"?
   */
  def toolPaths: Seq[File]
  def defaultLibraryPaths: Seq[File]
  def defaultIncludePaths: Seq[File]
  def ccExe: File
  def cxxExe: File
  def archiverExe: File
  def linkerExe: File
  def ccDefaultFlags: Seq[String]
  def cxxDefaultFlags: Seq[String]
  def archiveDefaultFlags: Seq[String]
  def dynamicLibraryLinkDefaultFlags: Seq[String]
  def executableLinkDefaultFlags: Seq[String]

  def findHeaderDependencies(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean = false): FunctionWithResultPath

  def ccCompileToObj(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean = false): FunctionWithResultPath
  def cxxCompileToObj(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean = false): FunctionWithResultPath
    
  def buildStaticLibrary(
    log: Logger,
    buildDirectory: File,
    libName: String,
    objectFiles: Seq[File],
    archiveFlags: Seq[String],
    quiet: Boolean = false): FunctionWithResultPath
  def buildSharedLibrary(
    log: Logger,
    buildDirectory: File,
    libName: String,
    objectFiles: Seq[File],
    linkPaths: Seq[File],
    linkLibraries: Seq[String],
    dynamicLibraryLinkFlags: Seq[String],
    quiet: Boolean = false): FunctionWithResultPath
  def buildExecutable(
    log: Logger,
    buildDirectory: File,
    exeName: String,
    executableLinkFlags: Seq[String],
    linkPaths: Seq[File],
    linkLibraries: Seq[String],
    inputFiles: Seq[File],
    quiet: Boolean = false): FunctionWithResultPath
}


trait CompilationProcess {
  protected def reportFileGenerated(
    log: Logger,
    genFile: File,
    quiet: Boolean) = if (!quiet) log.info(genFile.toString)
}

trait CompilerWithConfig extends Compiler {
  def buildTypeTrait: BuildTypeTrait
  def config: Config

  private val configPrefix = buildTypeTrait.pathDirs
  private def ton(d: Seq[String]) = d.mkString(".")

  override def toolPaths = config.getStringList(ton(configPrefix :+ "toolPaths")).map(file)
  override def defaultIncludePaths = config.getStringList(ton(configPrefix :+ "includePaths")).map(file)
  override def defaultLibraryPaths = config.getStringList(ton(configPrefix :+ "libraryPaths")).map(file)
  override def ccExe = file(config.getString(ton(configPrefix :+ "ccExe")))
  override def cxxExe = file(config.getString(ton(configPrefix :+ "cxxExe")))
  override def archiverExe = file(config.getString(ton(configPrefix :+ "archiver")))
  override def linkerExe = file(config.getString(ton(configPrefix :+ "linker")))
  override def ccDefaultFlags = config.getStringList(ton(configPrefix :+ "ccFlags"))
  override def cxxDefaultFlags = config.getStringList(ton(configPrefix :+ "cxxFlags"))
  override def archiveDefaultFlags = config.getStringList(ton(configPrefix :+ "archiveFlags"))
  override def dynamicLibraryLinkDefaultFlags = config.getStringList(ton(configPrefix :+ "dynamicLibraryLinkFlags"))
  override def executableLinkDefaultFlags = config.getStringList(ton(configPrefix :+ "executableLinkFlags"))
  
  def getCwd = (new java.io.File(".")).getCanonicalFile
}

/*case class NativeAnalysis[T]( val data : T, val warningLines : Seq[String] = Seq() )
{
    def addWarningLine( line : String ) = new NativeAnalysis( data, line +: warningLines )
}*/

/**
 * Build configurations for a particular project must inherit from this trait.
 * See the default in NativeDefaultBuild for more details
 */
trait BuildTypeTrait {
  def name: String
  def pathDirs: Seq[String]

  def isCrossCompile = false

  def targetDirectory(rootDirectory: File) =
    pathDirs.foldLeft(rootDirectory)(_ / _)
}

/**
 * Keys for a native build that should be visible from all types of SBT
 * project (including Scala).
 */
object NativeBuild
{
  val nativeExportedLibs = TaskKey[Seq[File]]("All libraries exported by this project")
  val nativeExportedLibDirectories = TaskKey[Seq[File]]("All library directories exported by this project")
  val nativeExportedIncludeDirectories = TaskKey[Seq[File]]("All include directories exported by this project")
}

/**
 * The base mechanics, keys and build graph for a native build.
 * The possible build configurations remain abstract via BuildType and
 * the configurations Set. These need to be provided in a derived class.
 */

abstract class NativeBuild extends Build
{
  import NativeBuild._

  lazy val parseOptions = ConfigParseOptions.defaults().setAllowMissing(true)
  lazy val defaultConf = ConfigFactory.load(getClass.getClassLoader)
  lazy val localConf = ConfigFactory.parseFile(file("build.conf").getAbsoluteFile, parseOptions)
  lazy val userConf = ConfigFactory.parseFile(file("user.conf").getAbsoluteFile, parseOptions)

  lazy val conf = userConf.withFallback(localConf).withFallback(defaultConf).resolve()

  lazy val ccFilePattern = Seq("*.c")
  lazy val cxxFilePattern = Seq("*.cpp", "*.cxx")

  lazy val buildRootDirectory =
    file(conf.getString("build.rootdirectory")).getAbsoluteFile / buildName

  private lazy val allProjectVals: Seq[Project] =
    ReflectUtilities.allVals[Project](this).values.toSeq

  val buildName: String

  type BuildType <: BuildTypeTrait

  case class BuildConfiguration(val conf: BuildType, val compiler: Compiler)

  def configurations: Set[BuildConfiguration]

  /**
   * Override this in your project to do appropriate checks on the 
   * build environment.
   */
  def checkConfiguration(log: Logger, env: BuildConfiguration) = {}
  
  val nativeCompiler = TaskKey[Compiler]("nativeCompiler", "Compiler to use for this build")
  val nativeBuildConfiguration = TaskKey[BuildConfiguration]("nativeBuildConfiguration", "Build configuration key")
  val nativeConfigRootBuildDirectory = TaskKey[File]("nativeConfigRootBuildDirectory", "Build root directory (for the config, not the project)")
  val nativeProjectBuildDirectory = TaskKey[File]("nativeProjectBuildDirectory", "Build directory for this config and project")
  val nativeStateCacheDirectory = TaskKey[File]("nativeStateCacheDirectory", "Build state cache directory")
  val nativeProjectDirectory = TaskKey[File]("nativeProjectDirectory", "Project directory")
  val nativeSourceDirectories = TaskKey[Seq[File]]("nativeSourceDirectories", "Source directories")
  val nativeIncludeDirectories = TaskKey[Seq[File]]("nativeIncludeDirectories", "Include directories")
  val nativeSystemIncludeDirectories = TaskKey[Seq[File]]("nativeSystemIncludeDirectories", "System include directories")
  val nativeLinkDirectories = TaskKey[Seq[File]]("nativeLinkDirectories", "Link directories")
  val nativeLibraries = TaskKey[Seq[String]]("nativeLibraries", "All native library dependencies for this project")
  val nativeCCSourceFiles = TaskKey[Seq[File]]("nativeCCSourceFiles", "All C source files for this project")
  val nativeCXXSourceFiles = TaskKey[Seq[File]]("nativeCXXSourceFiles", "All C++ source files for this project")
  val nativeCCSourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("nativeCCSourceFilesWithDeps", "All C source files with dependencies for this project")
  val nativeCXXSourceFilesWithDeps = TaskKey[Seq[(File, Seq[File])]]("nativeCXXSourceFilesWithDeps", "All C++ source files with dependencies for this project")
  val nativeObjectFiles = TaskKey[Seq[File]]("nativeObjectFiles", "All object files for this project")
  val nativeArchiveFiles = TaskKey[Seq[File]]("nativeArchiveFiles", "All archive files for this project, specified by full path")
  val nativeExe = TaskKey[File]("nativeExe", "Executable built by this project (if appropriate)")
  val nativeTestExe = TaskKey[Option[File]]("nativeTestExe", "Test executable built by this project (if appropriate)")
  val nativeRun = TaskKey[Unit]("nativeRun", "Perform a native run of this project")
  val nativeTestProject = TaskKey[Project]("nativeTestProject", "The test sub-project for this project")
  val nativeTestExtraDependencies = TaskKey[Seq[File]]("nativeTestExtraDependencies", "Extra file dependencies of the test (used to calculate when to re-run tests)")
  val nativeTest = TaskKey[Option[(File, File)]]("nativeTest", "Run the native test, returning the files with stdout and stderr respectively")
  val test = TaskKey[Unit]("test", "Run the test associated with this project")
  val nativeEnvironmentVariables = TaskKey[Seq[(String, String)]]("nativeEnvironmentVariables", "Environment variables to be set for running programs and tests")
  val nativeCleanAll = TaskKey[Unit]("nativeCleanAll", "Clean the entire build directory")
  val nativeCCCompileFlags = TaskKey[Seq[String]]("nativeCCCompileFlags", "Native C compile flags")
  val nativeCXXCompileFlags = TaskKey[Seq[String]]("nativeCXXCompileFlags", "Native C++ compile flags")
  val nativeArchiveFlags = TaskKey[Seq[String]]("nativeArchiveFlags", "Native archive flags (when creating archives/static libraries)")
  val nativeDynamicLibraryLinkFlags = TaskKey[Seq[String]]("nativeDynamicLibraryLinkFlags", "Native flags for linking dynamic libraries")
  val nativeExecutableLinkFlags = TaskKey[Seq[String]]("nativeExecutableLinkFlags", "Native flags for linking executables")

  // TODO: Give more meaningful name. 
  type Sett = Def.Setting[_]

  val buildOptsParser = Space ~> configurations.map(x => token(x.conf.name)).reduce(_ | _)

  val shCommandName = "sh"
  val nativeBuildConfigurationCommandName = "nativeBuildConfiguration"

  val configKey = AttributeKey[BuildConfiguration]("configKey")

  def setBuildConfigCommand = Command(nativeBuildConfigurationCommandName)(_ => buildOptsParser)
  { (state, configName) =>

    val configDict = configurations.map(x => (x.conf.name, x)).toMap
    val config = configDict(configName)

    val updatedAttributes = state.attributes.put(configKey, config)

    state.copy(attributes = updatedAttributes)
  }

  def shCommand(state: State, args: Seq[String]): State = {
    Process(args) !

    state
  }

  override def settings = super.settings ++ Seq(
    commands ++= BasicCommands.allBasicCommands ++ Seq(
      setBuildConfigCommand,
      Command.args("sh", "<args>")(shCommand)),

    nativeBuildConfiguration :=
    {
      val beo = state.value.attributes.get(configKey)

      if (beo.isEmpty)
      {
        val template = "Please set a build configuration using the %s command"
        val message = template.format(nativeBuildConfigurationCommandName)
        sys.error(message)
      }

      val config = beo.get
      val configCheckFile = config.conf.targetDirectory(buildRootDirectory) / "EnvHealthy.txt"

      if (!configCheckFile.exists)
      {
        checkConfiguration(state.value.log, config)
        IO.write(configCheckFile, "HEALTHY")
      }

      beo.get
    },

    shellPrompt :=
    { state =>
      val projectId = Project.extract(state).currentProject.id
      val config = state.attributes.get(configKey)
      
      "%s|%s:> ".format( config.map { _.conf.name }.getOrElse("No-config"), projectId)
    } )

  case class RichNativeProject(p: Project)
  {
    def nativeDependsOn(others: ProjectReference*): Project =
    {
      others.foldLeft(p) {
        case (np, other) =>
          np.dependsOn(other).settings(
            nativeIncludeDirectories in Compile ++= (nativeExportedIncludeDirectories in other).value,
            nativeLinkDirectories in Compile ++= (nativeExportedLibDirectories in other).value,
            nativeArchiveFiles in Compile ++= (nativeExportedLibs in other).value)
      }
    }

    def nativeSystemDependsOn(others: ProjectReference*): Project =
    {
      others.foldLeft(p)
      {
        case (np, other) =>
          np.dependsOn(other).settings(
            nativeSystemIncludeDirectories in Compile ++= (nativeExportedIncludeDirectories in other).value,
            nativeLinkDirectories in Compile ++= (nativeExportedLibDirectories in other).value,
            nativeArchiveFiles in Compile ++= (nativeExportedLibs in other).value)
      }
    }
  }

  implicit def toRichNativeProject(p: Project) = new RichNativeProject(p)

  object NativeProject {
    // A selection of useful default settings from the standard sbt config
    lazy val relevantSbtDefaultSettings = Seq[Sett](
    
      watchTransitiveSources := Defaults.watchTransitiveSourcesTask.value,
      
      watch := Defaults.watchSetting.value
    )

    lazy val configSettings = Seq(
      target := buildRootDirectory / name.value,

      historyPath := Some( target.value / ".history" ),

      nativeConfigRootBuildDirectory := nativeBuildConfiguration.value.conf.targetDirectory( target.value ),
      
      clean := IO.delete( nativeConfigRootBuildDirectory.value ),

      nativeCleanAll := IO.delete( target.value ),

      nativeCompiler := nativeBuildConfiguration.value.compiler,

      nativeProjectBuildDirectory :=
      {
        val dir = nativeConfigRootBuildDirectory.value

        IO.createDirectory(dir)

        dir
      },

      nativeStateCacheDirectory := nativeProjectBuildDirectory.value / "state-cache",

      nativeSystemIncludeDirectories := nativeCompiler.value.defaultIncludePaths,

      nativeTest := None,

      nativeExportedLibs := Seq(),
      nativeExportedLibDirectories := Seq(),
      nativeExportedIncludeDirectories := Seq(),
      nativeExe := file(""))
      
    def scheduleTasks[T]( tasks : Seq[sbt.Def.Initialize[sbt.Task[T]]] ) = Def.taskDyn { tasks.joinWith( _.join ) }
      
    def findDependencies(sourceFile: File) = Def.task
    {
      val depGen = nativeCompiler.value.findHeaderDependencies(
        state.value.log,
        nativeProjectBuildDirectory.value,
        nativeIncludeDirectories.value,
        nativeSystemIncludeDirectories.value,
        sourceFile,
        nativeCCCompileFlags.value)

      depGen.runIfNotCached(nativeStateCacheDirectory.value, Seq(sourceFile))

      (sourceFile, IO.readLines(depGen.resultPath).map(file))
    }

    def buildSettings = Seq(
      nativeCCSourceFiles := nativeSourceDirectories.value.flatMap( sd => ccFilePattern.flatMap(fp => (sd * fp).get) ),

      nativeCXXSourceFiles := nativeSourceDirectories.value.flatMap( sd => cxxFilePattern.flatMap(fp => (sd * fp).get) ),

      nativeCCCompileFlags := nativeCompiler.value.ccDefaultFlags,

      nativeCXXCompileFlags := nativeCompiler.value.cxxDefaultFlags,

      nativeLinkDirectories := nativeCompiler.value.defaultLibraryPaths,

      nativeLibraries := Seq(),

      nativeArchiveFiles := Seq(),

      nativeArchiveFlags := nativeCompiler.value.archiveDefaultFlags,
      
      nativeDynamicLibraryLinkFlags := nativeCompiler.value.dynamicLibraryLinkDefaultFlags,
      
      nativeExecutableLinkFlags := nativeCompiler.value.executableLinkDefaultFlags,
      
      nativeCCSourceFilesWithDeps := Def.taskDyn
      {
        nativeCCSourceFiles.value.map { findDependencies _ }.joinWith( _.join )
      }.value,
        
        
      nativeCXXSourceFilesWithDeps := Def.taskDyn
      {
        nativeCXXSourceFiles.value.map { findDependencies _ }.joinWith( _.join )
      }.value,

      nativeEnvironmentVariables := Seq(),
      
      nativeObjectFiles := Def.taskDyn
      {
        val ccTasks = nativeCCSourceFilesWithDeps.value.map
        { case (sourceFile, dependencies) =>
        
          val blf = nativeCompiler.value.ccCompileToObj(
            state.value.log,
            nativeProjectBuildDirectory.value,
            nativeIncludeDirectories.value,
            nativeSystemIncludeDirectories.value,
            sourceFile,
            nativeCCCompileFlags.value )

          Def.task { blf.runIfNotCached(nativeStateCacheDirectory.value, sourceFile +: dependencies) }
        }
        
        val cxxTasks = nativeCXXSourceFilesWithDeps.value.map
        { case (sourceFile, dependencies) =>
        
          val blf = nativeCompiler.value.cxxCompileToObj(
            state.value.log,
            nativeProjectBuildDirectory.value,
            nativeIncludeDirectories.value,
            nativeSystemIncludeDirectories.value,
            sourceFile,
            nativeCXXCompileFlags.value )

          Def.task { blf.runIfNotCached(nativeStateCacheDirectory.value, sourceFile +: dependencies) }
        }

        (ccTasks ++ cxxTasks).joinWith( _.join )
      }.value )

    def compileSettings = inConfig(Compile)(buildSettings ++ Seq[Sett](
      nativeSourceDirectories := Seq( nativeProjectDirectory.value / "source" ),
      nativeIncludeDirectories := Seq( nativeProjectDirectory.value / "interface", nativeProjectDirectory.value / "include" )
    ))

    def testSettings = inConfig(Test)(buildSettings ++ Seq[Sett](
      nativeProjectDirectory := (nativeProjectDirectory in Compile).value / "test",
      nativeProjectBuildDirectory :=
      {
        val testBd = (nativeProjectBuildDirectory in Compile).value / "test"
        IO.createDirectory(testBd)
        testBd
      },
      nativeIncludeDirectories := Seq( nativeProjectDirectory.value / "include" ),
      nativeIncludeDirectories ++= (nativeIncludeDirectories in Compile).value,
      nativeIncludeDirectories ++= (nativeExportedIncludeDirectories in Compile).value,
      nativeLinkDirectories ++= (nativeLinkDirectories in Compile).value,
      nativeArchiveFiles ++= (nativeArchiveFiles in Compile).value,
      nativeSourceDirectories := Seq( nativeProjectDirectory.value / "source" ),

      nativeTestExe :=
      {
        if ( nativeObjectFiles.value.isEmpty )
        {
          streams.value.log.info( "No tests defined for: " + name.value )
          None
        }
        else
        {
          val allInputFiles = nativeObjectFiles.value ++ (nativeExportedLibs in Compile).value ++ nativeArchiveFiles.value
          val blf = nativeCompiler.value.buildExecutable(
            streams.value.log,
            nativeProjectBuildDirectory.value,
            name.value + "_test",
            nativeExecutableLinkFlags.value,
            nativeLinkDirectories.value,
            nativeLibraries.value,
            allInputFiles )
          Some( blf.runIfNotCached( nativeStateCacheDirectory.value, allInputFiles ) )
        }
      },

      nativeTestExtraDependencies := ((nativeProjectDirectory.value / "data") ** "*").get,
      
      nativeTest :=
      {
        if ( !nativeBuildConfiguration.value.conf.isCrossCompile && nativeTestExe.value.isDefined )
        {
          val texe = nativeTestExe.value.get
          
          val resFile = file(texe + ".res")
          val stdoutFile = file(texe + ".stdout")

          val tcf = FunctionWithResultPath(stdoutFile) { _ =>
            streams.value.log.info("Running test: " + texe)

            val po = ProcessHelper.runProcess(
              streams.value.log,
              Seq(texe.toString),
              nativeProjectDirectory.value,
              (nativeEnvironmentVariables in Test).value,
              quiet = true)

            IO.writeLines(stdoutFile, po.stdout)
            IO.writeLines(resFile, Seq(po.retCode.toString))

          }

          tcf.runIfNotCached(nativeStateCacheDirectory.value, texe +: nativeTestExtraDependencies.value)

          Some((resFile, stdoutFile))
        }
        else
        {
          None
        }
      },

      compile <<= (nativeTestExe) map { nc => sbt.inc.Analysis.Empty },

      test :=
      {
        nativeTest.value map
        { case (resFile, stdOutFile) =>
        
          val res = IO.readLines( resFile ).head.toInt
          if ( res != 0 )
          {
            streams.value.log.error( "Test failed: " + name.value )
            IO.readLines( stdOutFile ).foreach { l => streams.value.log.info(l) }
            sys.error( "Non-zero exit code: " + res.toString )
          }
        }
      }))

    lazy val baseSettings = relevantSbtDefaultSettings ++ configSettings ++
        inConfig(Compile)(compileSettings) ++ Seq(
          watchSources ++=
          {
            val ccsfd = (nativeCCSourceFilesWithDeps in Compile).value
            val cxxsfd = (nativeCXXSourceFilesWithDeps in Compile).value

            (ccsfd ++ cxxsfd).flatMap
            {
              case (sf, deps) => (sf +: deps.toList)
            }.toList.distinct
          },
          watchSources ++=
          {
            val ccsfd = (nativeCCSourceFilesWithDeps in Test).value
            val cxxsfd = (nativeCXXSourceFilesWithDeps in Test).value

            (ccsfd ++ cxxsfd).flatMap
            {
              case (sf, deps) => (sf +: deps.toList)
            }.toList.distinct
          })


    lazy val staticLibrarySettings = baseSettings ++ Seq(
      nativeExportedLibs :=
      {
        val ofs = (nativeObjectFiles in Compile).value
        
        if ( ofs.isEmpty ) Seq()
        else
        {
          val blf = nativeCompiler.value.buildStaticLibrary(
            streams.value.log,
            nativeProjectBuildDirectory.value,
            name.value,
            ofs,
            (nativeArchiveFlags in Compile).value )
            
          Seq( blf.runIfNotCached(nativeStateCacheDirectory.value, ofs) )
        }
      },
      nativeExportedIncludeDirectories := Seq( (nativeProjectDirectory in Compile).value / "interface" ),
      nativeExportedLibDirectories := nativeExportedLibs.value.map( _.getParentFile ).distinct,
      compile in Compile := { nativeExportedLibs.value; sbt.inc.Analysis.Empty }
    ) ++ testSettings

    lazy val sharedLibrarySettings = baseSettings ++ Seq(
      nativeExportedLibs :=
      {
        val allInputFiles = (nativeObjectFiles in Compile).value ++ (nativeArchiveFiles in Compile).value
        
        val blf = nativeCompiler.value.buildSharedLibrary(
          streams.value.log,
          nativeProjectBuildDirectory.value,
          name.value,
          allInputFiles,
          (nativeLinkDirectories in Compile).value,
          (nativeLibraries in Compile).value,
          (nativeDynamicLibraryLinkFlags in Compile).value
        )
        
        Seq( blf.runIfNotCached( nativeStateCacheDirectory.value, allInputFiles ) )
      },
      nativeExportedIncludeDirectories := Seq( (nativeProjectDirectory in Compile).value / "interface" ),
      nativeExportedLibDirectories := nativeExportedLibs.value.map(_.getParentFile).distinct,
      compile in Compile := { nativeExportedLibs.value; sbt.inc.Analysis.Empty }
    ) ++ testSettings

    lazy val nativeExeSettings = baseSettings ++ inConfig(Compile)( Seq(
      nativeExe in Compile :=
      {
        val allInputFiles = nativeObjectFiles.value ++ nativeArchiveFiles.value
        
        val blf = nativeCompiler.value.buildExecutable(
          streams.value.log,
          nativeProjectBuildDirectory.value,
          name.value,
          nativeExecutableLinkFlags.value,
          nativeLinkDirectories.value,
          nativeLibraries.value,
          allInputFiles )

        blf.runIfNotCached(nativeStateCacheDirectory.value, allInputFiles)
      },
      nativeTestExe in Test := None,
      
      compile in Compile := { nativeExe.value; sbt.inc.Analysis.Empty },
      
      run :=
      {
        val args: Seq[String] = spaceDelimited("<arg>").parsed
        val res = Process( nativeExe.value.toString +: args, nativeProjectDirectory.value, nativeEnvironmentVariables.value : _* ) !
        
        if (res != 0) sys.error("Non-zero exit code: " + res.toString)
      } ))

    def apply(
      _name: String,
      _projectDirectory: File,
      _settings: => Seq[Def.Setting[_]]): Project =
    {
      Project(
        id = _name,
        base = _projectDirectory,
        settings = Seq(
          name := _name,
          baseDirectory := _projectDirectory,
          nativeProjectDirectory in Compile := baseDirectory.value )
          ++ _settings )
    }
  }
}



