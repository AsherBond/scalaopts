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

package scalaopts.java

import scalaopts.{CustomOptionParser, TypedCommandLineOption}
import collection.JavaConversions

/**
 */
object Translate {

  def asStringSeq(arr: Array[String]): Seq[String] = arr

  def asTypedCommandLineOption[T](opt: ICommandLineOption[T]): TypedCommandLineOption[T] = {
    val java_option_parser = opt.getOptionParser()
    val java_default = java_option_parser.getDefaultValue()
    val default = Option(java_default)
    val long_names = JavaConversions.iterableAsScalaIterable(opt.getLongNames()).toList
    val short_names = JavaConversions.iterableAsScalaIterable(opt.getShortNames()).toList
    val dependencies = JavaConversions.iterableAsScalaIterable(opt.getDependencies).toList

    val option_parser = new CustomOptionParser[T](default, java_option_parser.isDefaultValueUsed(), java_option_parser.isAssociatedValueRequired(), (s: String) => {
      Option(java_option_parser.getTransform()) match {
        case None => throw new IllegalStateException("Missing definition of IOptionTransform")
        case Some(t) => Option(t(s))
      }
    })

    //TODO: Fill in the accumulator -- define this for the Java side of the house

    new TypedCommandLineOption[T](opt.getName(), opt.isRequired(), long_names, short_names, dependencies, opt.getDescription(), opt.getArity(), opt.getMinNumberOfRequiredValues(), opt.getMaxNumberOfRequiredValues(), Some(option_parser), None)
  }

  def asTypedCommandLineOptionSeq[T](options: Array[ICommandLineOption[T]]): Seq[TypedCommandLineOption[_]] = {
    for (opt <- options)
      yield asTypedCommandLineOption(opt)
  }

  def asParserConfiguration(configuration: IParserConfiguration): scalaopts.ParserConfiguration = {
    //TODO: Translate to ParserStrategy instance
    //Just create a default strategy for now...
    //val java_parser_strategy = configuration.getStrategy()
    //new scalaopts.ParserConfiguration()...
    scalaopts.CommandLineOptions.DEFAULT_PARSER_CONFIGURATION
  }

  def asParser[T](options: Array[ICommandLineOption[T]]): scalaopts.Parser = {
    scalaopts.CommandLineOptions.applySeq(asTypedCommandLineOptionSeq(options))
  }

  def asParser[T](configuration: IParserConfiguration, options: Array[ICommandLineOption[T]]): scalaopts.Parser = {
    scalaopts.CommandLineOptions.applySeq(asParserConfiguration(configuration))(asTypedCommandLineOptionSeq(options))
  }
}
