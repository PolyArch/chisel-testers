// See LICENSE for license details.

package chisel3.iotesters

import java.io.File

import chisel3._
import chisel3.experimental.MultiIOModule
import firrtl.options.{DriverExecutionResult, ExecutionOptionsManager}
import firrtl.options.Viewer.view
import firrtl.{AnnotationSeq, FirrtlExecutionOptions, FirrtlSourceAnnotation, HasFirrtlExecutionOptions}
import firrtl.FirrtlViewer._
import iotesters.TesterOptionsViewer._
import logger.LoggerViewer._
import firrtl.FirrtlViewer._
import logger.{Logger, LoggerOptions}

import scala.util.DynamicVariable

object Driver extends firrtl.options.Driver {
  private val backendVar = new DynamicVariable[Option[Backend]](None)

  private[iotesters] def backend = backendVar.value

  private val globalAnnotationsVar = new DynamicVariable[Option[AnnotationSeq]](None)

  private[iotesters] def globalAnnotations = globalAnnotationsVar.value

  val optionsManager: ExecutionOptionsManager = {
    new ExecutionOptionsManager("testers") with HasFirrtlExecutionOptions
  }

  case class IoTestersExecutionResult(result: Boolean = true) extends DriverExecutionResult

  def execute(args: Array[String], initialAnnotations: AnnotationSeq): DriverExecutionResult = {
    val annotations = optionsManager.parse(args) ++ initialAnnotations

    val loggerOptions = view[LoggerOptions](annotations).get
    val testerOptions = view[TesterOptions](annotations).get

    Logger.makeScope(loggerOptions) {
      globalAnnotationsVar.withValue(Some(annotations)) {

        val dutGenerator = annotations.collectFirst { case gen: ChiselDutGeneratorAnnotation[_] => gen } match {
          case Some(ChiselDutGeneratorAnnotation(gen)) => gen
          case _ =>
            throw new Exception(s"CircuitGenerator (dutGenerator) not specified")
        }

        val testerGenerator = annotations.collectFirst { case gen: PeekPokeTesterAnnotation[_] => gen } match {
          case Some(PeekPokeTesterAnnotation(gen)) => gen
          case _ =>
            throw new Exception(s"CircuitGenerator (testerGenerator) not specified")
        }

        val (dut, backend) = testerOptions.backendName match {
          case "firrtl" =>
            val (tempdut, tempannos) = setupFirrtlTerpBackend(dutGenerator, annotations)
            (tempdut, tempannos)
          case "treadle" =>
            setupTreadleBackend(dutGenerator, annotations)
          case "verilator" =>
            setupVerilatorBackend(dutGenerator, annotations)
          //TODO: chick fix these backends
          //        case "ivl" =>
          //          setupIVLBackend(dutGenerator, optionsManager)
          //        case "vcs" =>
          //          setupVCSBackend(dutGenerator, optionsManager)
          case _ =>
            throw new Exception(s"Unrecognized backend name ${testerOptions.backendName}")
        }

        backendVar.withValue(Some(backend)) {
          try {
            testerGenerator(dut).finish
          } catch {
            case e: Throwable =>
              e.printStackTrace()
              //              backend match {
              //                case b: IVLBackend => TesterProcess.kill(b)
              //                case b: VCSBackend => TesterProcess.kill(b)
              //                case b: VerilatorBackend => TesterProcess.kill(b)
              //                case _ =>
              //              }
              throw e
          }
        }
      }
    }

    IoTestersExecutionResult()
  }

  /**
    * This executes a test harness that extends peek-poke tester upon a device under test
    * with an optionsManager to control all the options of the toolchain components
    *
    * @param dutGenerator   The device under test, a subclass of a Chisel3 module
    * @param optionsManager Use this to control options like which backend to use
    * @param testerGen      A peek poke tester with tests for the dut
    * @return Returns true if all tests in testerGen pass
    */
  def execute[T <: MultiIOModule](
    dutGenerator  : () => T,
    optionsManager: TesterOptionsManager
  )
  (
    testerGen: T => PeekPokeTester[T]
  ): Boolean = {

    val generatorAnnotation = ChiselDutGeneratorAnnotation(dutGenerator)
    val testerAnnotation = PeekPokeTesterAnnotation[T](testerGen)

    execute(Array.empty[String], Seq(generatorAnnotation, testerAnnotation)) match {
      case IoTestersExecutionResult(result) => result
      case _ => false
    }
  }

  /**
    * This executes the test with options provide from an array of string -- typically provided from the
    * command line
    *
    * @param args      A *main* style array of string options
    * @param dut       The device to be tested, (device-under-test)
    * @param testerGen A peek-poke tester with test for the dey
    * @return Returns true if all tests in testerGen pass
    */
  def execute[T <: MultiIOModule](args: Array[String], dut: () => T)(testerGen: T => PeekPokeTester[T]): Boolean = {

    val annotations = Seq(chisel3.ChiselDutGeneratorAnnotation(dut), PeekPokeTesterAnnotation(testerGen))
    execute(args, annotations) match {
      case IoTestersExecutionResult(result) => result
      case _ => false
    }
  }

  def apply[T <: MultiIOModule](
    dutGen: () => T,
    backendType: String = "firrtl",
    verbose: Boolean = false,
    testerSeed: Long = System.currentTimeMillis()
  )(
    testerGen: T => PeekPokeTester[T]
  ): Boolean = {
    true
  }

  //  /**
  //    * Start up the interpreter repl with the given circuit
  //    * To test a `class X extends Module {}`, add the following code to the end
  //    * of the file that defines
  //    *
  //    * @example {{{
  //    *           object XRepl {
  //    *             def main(args: Array[String]) {
  //    *               val optionsManager = new ReplOptionsManager
  //    *               if(optionsManager.parse(args)) {
  //    *                 iotesters.Driver.executeFirrtlRepl(() => new X, optionsManager)
  //    *               }
  //    *             }
  //    * }}}
  //    * running main will place users in the repl with the circuit X loaded into the repl
  //    * @param dutGenerator   Module to run in interpreter
  //    * @param optionsManager options
  //    * @return
  //    */
  //  def executeFirrtlRepl[T <: MultiIOModule](
  //                                      dutGenerator: () => T,
  //                                      optionsManager: ReplOptionsManager = new ReplOptionsManager): Boolean = {
  //
  //    if (optionsManager.topName.isEmpty) {
  //      if (optionsManager.targetDirName == ".") {
  //        optionsManager.setTargetDirName("test_run_dir")
  //      }
  //      val genClassName = dutGenerator.getClass.getName
  //      val testerName = genClassName.split("""\$\$""").headOption.getOrElse("") + genClassName.hashCode.abs
  //      optionsManager.setTargetDirName(s"${optionsManager.targetDirName}/$testerName")
  //    }
  //
  //    optionsManager.chiselOptions = optionsManager.chiselOptions.copy(runFirrtlCompiler = false)
  //    optionsManager.firrtlOptions = optionsManager.firrtlOptions.copy(compilerName = "low")
  //
  //    Logger.makeScope(optionsManager) {
  //      val chiselResult: ChiselExecutionResult = chisel3.Driver.execute(optionsManager, dutGenerator)
  //      chiselResult match {
  //        case ChiselExecutionSuccess(_, emitted, _) =>
  //          optionsManager.replConfig = optionsManager.replConfig.copy(firrtlSource = emitted)
  //          FirrtlRepl.execute(optionsManager)
  //          true
  //        case ChiselExecutionFailure(message) =>
  //          println("Failed to compile circuit")
  //          false
  //      }
  //    }
  //  }
  //  /**
  //    * Start up the interpreter repl with the given circuit
  //    * To test a `class X extends Module {}`, add the following code to the end
  //    * of the file that defines
  //    * @example {{{
  //    *           object XRepl {
  //    *             def main(args: Array[String]) {
  //    *               iotesters.Driver.executeFirrtlRepl(args, () => new X)
  //    *             }
  //    *           }
  //    * }}}
  //    * running main will place users in the repl with the circuit X loaded into the repl
  //    *
  //    * @param dutGenerator   Module to run in interpreter
  //    * @param args           options from the command line
  //    * @return
  //    */
  //  def executeFirrtlRepl[T <: MultiIOModule](
  //                                    args: Array[String],
  //                                      dutGenerator: () => T
  //                                      ): Boolean = {
  //    val optionsManager = new ReplOptionsManager
  //
  //    if(optionsManager.parse(args)) {
  //      executeFirrtlRepl(dutGenerator, optionsManager)
  //    }
  //    else {
  //      false
  //    }
  //  }
  //  /**
  //    * This is just here as command line way to see what the options are
  //    * It will not successfully run
  //    * TODO: Look into dynamic class loading as way to make this main useful
  // *
  //    * @param args unused args
  //    */
  //  def main(args: Array[String]) {
  //    execute(Array("--help"), null)(null)
  //  }
  //  /**
  //    * Runs the ClassicTester and returns a Boolean indicating test success or failure
  //    * @@backendType determines whether the ClassicTester uses verilator or the firrtl interpreter to simulate
  //    * the circuit.
  //    * Will do intermediate compliation steps to setup the backend specified, including cpp compilation for the
  //    * verilator backend and firrtl IR compilation for the firrlt backend
  //    *
  //    * This apply method is a convenient short form of the [[Driver.execute()]] which has many more options
  //    *
  //    * The following tests a chisel CircuitX with a CircuitXTester setting the random number seed to a fixed value and
  //    * turning on verbose tester output.  The result of the overall test is put in testsPassed
  //    *
  //    * @example {{{
  //    *           val testsPassed = iotesters.Driver(() => new CircuitX, testerSeed = 0L, verbose = true) { circuitX =>
  //    *             CircuitXTester(circuitX)
  //    *           }
  //    * }}}
  //    *
  //
  //    * @param dutGen      This is the device under test.
  //    * @param backendType The default backend is "firrtl" which uses the firrtl interperter. Other options
  //    *                    "verilator" will use the verilator c++ simulation generator
  //    *                    "ivl" will use the Icarus Verilog simulation
  //    *                    "vcs" will use the VCS simulation
  //    * @param verbose     Setting this to true will make the tester display information on peeks,
  //    *                    pokes, steps, and expects.  By default only failed expects will be printed
  //    * @param testerSeed  Set the random number generator seed
  //    * @param testerGen   This is a test harness subclassing PeekPokeTester for dutGen,
  //    * @return            This will be true if all tests in the testerGen pass
  //    */
  //  def apply[T <: MultiIOModule](
  //      dutGen: () => T,
  //      backendType: String = "firrtl",
  //      verbose: Boolean = false,
  //      testerSeed: Long = System.currentTimeMillis())(
  //      testerGen: T => PeekPokeTester[T]): Boolean = {
  //
  //    val optionsManager = new TesterOptionsManager {
  //      testerOptions = testerOptions.copy(backendName = backendType, isVerbose = verbose, testerSeed = testerSeed)
  //    }
  //
  //    execute(dutGen, optionsManager)(testerGen)
  //  }
  //
  /**
    * Runs the ClassicTester using the verilator backend without doing Verilator compilation and returns a Boolean indicating success or failure
    * Requires the caller to supply path the already compile Verilator binary
    */
  def run[T <: MultiIOModule](dutGen: () => T, cmd: Seq[String])
    (testerGen: T => PeekPokeTester[T]): Boolean = {
    val circuit = chisel3.Driver.elaborate(dutGen)
    val dut = getTopModule(circuit).asInstanceOf[T]
//    backendVar.withValue(Some(new VerilatorBackend(dut, cmd))) {
//      try {
//        testerGen(dut).finish
//      } catch {
//        case e: Throwable =>
//          e.printStackTrace()
//          backend match {
//            case Some(b: IVLBackend) =>
//              TesterProcess kill b
//            case Some(b: VCSBackend) =>
//              TesterProcess kill b
//            case Some(b: VerilatorBackend) =>
//              TesterProcess kill b
//            case _ =>
//          }
//          throw e
//      }
//    }
    true
  }

  def run[T <: MultiIOModule](dutGen: () => T, binary: String, args: String*)
    (testerGen: T => PeekPokeTester[T]): Boolean =
    run(dutGen, binary +: args.toSeq)(testerGen)

  def run[T <: MultiIOModule](dutGen: () => T, binary: File, waveform: Option[File] = None)
    (testerGen: T => PeekPokeTester[T]): Boolean = {
    val args = waveform match {
      case None => Nil
      case Some(f) => Seq(s"+waveform=$f")
    }
    run(dutGen, binary.toString +: args)(testerGen)
  }
}
//}
//
//class ReplOptionsManager
//  extends InterpreterOptionsManager
//    with HasChiselExecutionOptions
//    with HasReplConfig
//
