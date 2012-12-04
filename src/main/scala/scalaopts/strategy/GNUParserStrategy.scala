/*
  Copyright (C) 2012-2013 the original author or authors.

  See the LICENSE.txt file distributed with this work for additional
  information regarding copyright ownership.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package scalaopts.strategy

import scalaopts._
import scalaopts.common.StringUtil._
import scala.math._
import annotation.tailrec
import util.logging.Logged

/**
 * GNU has laid out a set of rules for creating options and non-options, and
 * they are as follows:
 *
 * - Arguments are options if they begin with a hyphen delimiter (‘-’).
 * - Multiple options may follow a hyphen delimiter in a single token if the
 *   options do not take arguments. Thus, ‘-abc’ is equivalent to ‘-a -b -c’.
 * - Option names are single alphanumeric characters.
 * - Certain options require an argument. For example, the ‘-o’ command of the
 *   ld command requires an argument—an output file name.
 * - An option and its argument may or may not appear as separate tokens. (In
 *   other words, the whitespace separating them is optional.) Thus, ‘-o foo’
 *   and ‘-ofoo’ are equivalent.
 * - Options typically precede other non-option arguments.
 * - The argument ‘--’ terminates all options; any following arguments are
 *   treated as non-option arguments, even if they begin with a hyphen.
 * - A token consisting of a single hyphen character is interpreted as an
 *   ordinary non-option argument.
 * - Options may be supplied in any order, or appear multiple times. The
 *   interpretation is left up to the particular application program.
 *
 * GNU adds long options to these conventions. Long options consist of ‘--’
 * followed by a name made of alphanumeric characters and dashes. Option names
 * are typically one to three words long, with hyphens to separate words.
 * Users can abbreviate the option names as long as the abbreviations are
 * unique.
 *
 * To specify an argument for a long option, write ‘--name=value’. This syntax
 * enables a long option to accept an argument that is itself optional.
 */
class GNUParserStrategy extends ParserStrategy {
  /* Configure the logger. */
  private[GNUParserStrategy] object Log {
    val (logger, formatter) = ZeroLoggerFactory.newLogger(this)
  }

  import Log.logger
  import Log.formatter._

  val SHORT_OPTION_PREFIX = "-"
  val LONG_OPTION_PREFIX = "--"
  val NON_OPTION_ARGUMENT = "-"
  val TERMINATOR = "--"

  def isTerminator(s: String): Boolean = TERMINATOR.equals(s)
  def isNonOptionArgument(s: String): Boolean = NON_OPTION_ARGUMENT.equals(s)
  def isLongCommandLineOption(s: String): Boolean = s.startsWith(LONG_OPTION_PREFIX)
  def isShortCommandLineOption(s: String): Boolean = s.startsWith(SHORT_OPTION_PREFIX)
  def isCommandLineOption(s: String): Boolean = isLongCommandLineOption(s) || isShortCommandLineOption(s)

  def validateOptions(options: CommandLineOptionMap): Boolean = {
    //Validate that all short names are of length 1 and each name is alphanumeric.
    val invalid_short_name_option = options.find(_._2._1.shortNames.exists(name => name.length != 1 || !Character.isLetterOrDigit(name.charAt(0))))
    if (invalid_short_name_option.isDefined) {
      val option = invalid_short_name_option.get._2._1
      throw new IllegalArgumentException("All short names must be 1 character in length and alpha-numeric for GNU-style parsing. The following option violated this rule: (name: " + option.name + ", short names: [" + (option.shortNames mkString ", ") + "])")
    }

    //Validate that all long names are composed of alpha-numeric characters or dashes.
    val invalid_long_name_option = options.find(_._2._1.longNames.exists(name => name.exists(c => !Character.isLetterOrDigit(c) && !('-' == c))))
    if (invalid_long_name_option.isDefined) {
      val option = invalid_long_name_option.get._2._1
      throw new IllegalArgumentException("All long names must be composed of alpha-numeric characters or hyphens for GNU-style parsing. The following option violated this rule: (name: " + option.name + ", long names: [" + (option.longNames mkString ", ") + "])")
    }

    true
  }

  /**
   * @see [[scalaopts.ParserStrategy.processOptions()]]
   */
  def processOptions(application_arguments: Stream[String], command_line_options: CommandLineOptionMap): Stream[StandardOption[_]] = {
    val findCommandLineOption = findMatchingCommandLineOption(command_line_options)_
    val findCommandLineOptionByLongName = findMatchingCommandLineOptionByLongName(command_line_options)_
    val findCommandLineOptionByShortName = findMatchingCommandLineOptionByShortName(command_line_options)_

    @tailrec
    def processOptions0(application_arguments: Stream[String]): Stream[StandardOption[_]] = {
      application_arguments match {
        case potential_option #:: tail if potential_option.isNonEmpty && isCommandLineOption(potential_option) => {
          logger.info(_ ++= "Examining " ++= potential_option)

          if (isTerminator(potential_option)) {
            //No more option parsing if we hit a "--", everything from here on out should be considered
            //a non-option argument.
            //
            //We should do something more intelligent with this -- provide a stream for non-option arguments?
            logger.info("Found terminator")
            Stream()
          } else if (isNonOptionArgument(potential_option)) {
            //Treat as a non-option argument. IOW, there's no value for this guy -- just let the
            //app process it.
            //
            //We should do something more intelligent with this -- provide a stream for non-option arguments?
            logger.info("Found non-option argument")
            Stream()
          } else if (isLongCommandLineOption(potential_option)) {
            val opt = stripLeadingHyphens(potential_option)
            val (name, value, equals_found) = splitAtEquals(opt)

            logger.info(_ ++= "Found long option (name: " ++= name ++= ", value: " ++= value ++= ")")

            findCommandLineOptionByLongName(name) match {
              case None => {
                unrecognizedOption(name)
                Stream()
              }
              case Some((command_line_option, initial_accumulated_value)) => {
                //If there's an equals sign then process this value and any remaining required values
                if (equals_found) {

                  //We found at least one option argument, so evaluate it.
                  processSingleOptionArgument(command_line_option, value)

                  //Evaluate any other remaining arguments.
                  processOptions0(processOptionArguments(command_line_option, 1, command_line_option.maxNumberOfArguments - 1, tail))
                } else if (command_line_option.isFlag) {
                  //This is a flag, but it should still be evaluated.
                  processSingleOptionArgument(command_line_option, empty)

                  //Continue processing.
                  processOptions0(tail)
                } else {
                  invalidFormat(name, "Missing equals sign for option")
                  Stream()
                }
              }
            }
          } else if (isShortCommandLineOption(potential_option)) {

            //Get the list of 1+ options
            //Recall that an option like "-abc" is actually equivalent to "-a -b -c" (assuming they're all flags)
            val potentially_multiple_options = stripLeadingHyphens(potential_option)

            logger.info(_ ++= "Found short option(s): " ++= potentially_multiple_options)

            //Check if the first character is an option and *NOT* a flag. If so, treat the rest as a value for that option.
            //Otherwise, pick it off, prepend to the arg stream a hyphen and the rest of the current arg and continue processing.
            potentially_multiple_options.headOption match {
              case None => {
                //Nothing to see here...move along...
                //Apparently there's nothing left to examine.
                processOptions0(tail)
              }
              case Some(char_name) => {
                //Convert the character to a string.
                val name = char_name.toString

                //Do we have a short name option by this name?
                findCommandLineOptionByShortName(name) match {
                  case None => {
                    //I don't know who you're talking about so error out of here.
                    unrecognizedOption(name)
                    Stream()
                  }
                  case Some((command_line_option, initial_accumulated_value)) => {
                    //Found an option by that name. Excellent.
                    //Let's see if you're a flag or not. If you're not, then the remaining
                    //text is a value.

                    logger.fine(_ ++= "Recognized option (name: " ++= command_line_option.name ++= ")")

                    val remaining = potentially_multiple_options.tail

                    if (!command_line_option.isFlag) {
                      processOptions0(processOptionArguments(command_line_option, 0, command_line_option.maxNumberOfArguments, remaining #:: tail))
                    } else {
                      //This is a flag, but it should still be evaluated.
                      processSingleOptionArgument(command_line_option, empty)

                      //Cycle around again, fooling the code into thinking that we're looking at another
                      //short name. This could result in some interesting scenarios. e.g.:
                      //-ooo: Is that the same flag 3 times? Or is it -o with a value of "oo"?
                      //-abc where a is a flag and b is not: Should c be a value for b then?
                      if (remaining.isNonEmpty) {
                        processOptions0((SHORT_OPTION_PREFIX + remaining) #:: tail)
                      } else {
                        processOptions0(processOptionArguments(command_line_option, 0, command_line_option.maxNumberOfArguments, tail))
                      }
                    }
                  }
                }
              }
            }
          } else {
            processOptions0(tail)
          }
        }
        case arg #:: tail => {
          logger.warning(_ ++= "Failed to recognize argument: " ++= arg)
          processOptions0(tail)
        }
        case _ => Stream()
      }
    }

    def processOptionArguments(mapValue: CommandLineOptionMapTypedValue, valuesFound: Int, valuesRemaining: Int, args: Stream[String]): Stream[String] = {
      @tailrec
      def processOptionArguments0(valuesFound: Int, valuesRemaining: Int, args: Stream[String]): Stream[String] = {
        logger.finer("processing remaining option arguments")

        args match {
          case arg #:: tail if !isCommandLineOption(arg) && findCommandLineOption(arg).isEmpty => {
            logger.finer(_ ++= "found option argument: " ++= arg)

            //Ensure we haven't exceeded the max number of arguments for this option.
            if (mapValue.isMaxNumberOfArgumentsUnbounded || valuesRemaining > 0) {
              processSingleOptionArgument(mapValue, arg)
              processOptionArguments0(if (!mapValue.isMinNumberOfArgumentsUnbounded) min(valuesFound + 1, mapValue.minNumberOfArguments) else UNBOUNDED, if (!mapValue.isMaxNumberOfArgumentsUnbounded) max(valuesRemaining - 1, -1) else UNBOUNDED, tail)
            } else {
              if (!mapValue.isMaxNumberOfArgumentsUnbounded && mapValue.maxNumberOfArguments > 0) {
                exceededMaximumNumberOfArguments(mapValue.name, mapValue.maxNumberOfArguments)
              }

              //return unmodified stream at this point so the caller
              //can continue inspecting the arguments at the point where
              //we've left off.
              //
              //IOW, we're explicitly NOT returning tail!
              args
            }
          }
          case _ => {
            logger.finer("no more option arguments, continuing on")

            //Validate that we've met the minimum number of required arguments for this option.
            if (!mapValue.isMinNumberOfArgumentsUnbounded && valuesFound < mapValue.minNumberOfArguments) {
              missingMinimumNumberOfArguments(mapValue.name, valuesFound, mapValue.minNumberOfArguments)
            }

            //return unmodified stream at this point so the caller
            //can continue inspecting the arguments at the point where
            //we've left off.
            //
            //IOW, we're explicitly NOT returning tail!
            args
          }
        }
      }

      processOptionArguments0(valuesFound, valuesRemaining, args)
    }

    def processSingleOptionArgument(mapValue: CommandLineOptionMapTypedValue, currentValue: String): Unit = {
      logger.info(_ ++= "processing value for " ++= mapValue.name ++= ": " ++= currentValue)
      val result = mapValue(currentValue)
      logger.fine(_ ++= "ran option parser for " ++= mapValue.name ++= ", result: " ++= result.toString)
    }

    def unrecognizedOption(optionName: String): Unit = {
      logger.warning(_ ++= "unrecognized option: " ++= optionName)
    }

    def invalidFormat(optionName: String, description: String): Unit = {
      logger.warning(_ ++= "invalid format for option. " ++= description)
    }

    def missingMinimumNumberOfArguments(optionName: String, number_found: Int, minimum: Int): Unit = {
      logger.warning(_ ++= "missing minimum number of expected arguments for " ++= optionName ++= ": " ++= minimum.toString ++= ", found: " ++= number_found.toString)
    }

    def exceededMaximumNumberOfArguments(optionName: String, maximum: Int): Unit = {
      logger.warning(_ ++= "exceeded maximum number of expected option arguments for " ++= optionName ++= ": " ++= maximum.toString)
    }

    processOptions0(application_arguments)
  }
}
