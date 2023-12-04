/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.mobileharness.infra.ats.console.result.report;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.Entry.comparingByKey;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.devtools.mobileharness.api.model.error.ExtErrorId;
import com.google.devtools.mobileharness.api.model.error.MobileHarnessException;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.Attribute;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.BuildInfo;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.LoggedFile;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.Metric;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.Module;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.Reason;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.Result;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.Run;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.RunHistory;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.StackTrace;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.Summary;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.Test;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.TestCase;
import com.google.devtools.mobileharness.infra.ats.console.result.proto.ReportProto.TestFailure;
import com.google.devtools.mobileharness.infra.ats.console.result.xml.XmlConstants;
import com.google.devtools.mobileharness.shared.util.file.local.LocalFileUtil;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

/**
 * Parser to parse compatibitly xTS report XML file.
 *
 * <p>The compatibility report XML is generated by
 * com/android/tradefed/result/suite/XmlSuiteResultFormatter.java
 */
public class CompatibilityReportParser {

  private final LocalFileUtil localFileUtil;
  private final XMLInputFactory xmlInputFactory;

  @Inject
  CompatibilityReportParser(XMLInputFactory xmlInputFactory) {
    this(xmlInputFactory, new LocalFileUtil());
  }

  @VisibleForTesting
  CompatibilityReportParser(XMLInputFactory xmlInputFactory, LocalFileUtil localFileUtil) {
    // So that stack traces that aren't enclosed in CDATA are properly parsed
    xmlInputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
    this.xmlInputFactory = xmlInputFactory;
    this.localFileUtil = localFileUtil;
  }

  /**
   * Parses the compatibility report XML file and returns {@link Optional}<{@link Result}>.
   *
   * <p>If the given file doesn't exist, returns an empty {@link Optional}.
   */
  public Optional<Result> parse(Path reportXmlFile) throws MobileHarnessException {
    if (!localFileUtil.isFileExist(reportXmlFile)) {
      return Optional.empty();
    }

    Result.Builder resultBuilder = Result.newBuilder();
    try (InputStream reportInputStream =
        new BufferedInputStream(new FileInputStream(reportXmlFile.toFile()))) {
      Context context = new Context(resultBuilder);
      XMLEventReader xmlEventReader =
          xmlInputFactory.createXMLEventReader(reportInputStream, UTF_8.toString());
      while (xmlEventReader.hasNext()) {
        XMLEvent event = xmlEventReader.nextEvent();

        switch (event.getEventType()) {
          case XMLStreamConstants.START_ELEMENT:
            enteringTag(event.asStartElement(), context);
            break;
          case XMLStreamConstants.END_ELEMENT:
            exitingTag(event.asEndElement(), context);
            break;
          case XMLStreamConstants.CHARACTERS:
            characters(event.asCharacters(), context);
            break;
          default: // do nothing
        }
      }
    } catch (IOException ioe) {
      throw new MobileHarnessException(ExtErrorId.REPORT_PARSER_READ_XML_FILE_ERROR, "", ioe);
    } catch (XMLStreamException streamEx) {
      throw new MobileHarnessException(
          ExtErrorId.REPORT_PARSER_XML_EVENT_READER_ERROR, "", streamEx);
    }

    return Optional.of(resultBuilder.build());
  }

  private static void enteringTag(StartElement element, Context context) {
    context.tagStack.push(element);
    String elementName = element.getName().getLocalPart();
    switch (elementName) {
      case XmlConstants.RESULT_TAG:
        handleResult(element, context);
        break;
      case XmlConstants.BUILD_TAG:
        if (parentIs(context.tagStack, XmlConstants.RESULT_TAG)) {
          handleBuildInfo(element, context);
        }
        break;
      case XmlConstants.RUN_HISTORY_TAG:
        if (parentIs(context.tagStack, XmlConstants.RESULT_TAG)) {
          handleRunHistory(context);
        }
        break;
      case XmlConstants.RUN_TAG:
        if (parentIs(context.tagStack, XmlConstants.RUN_HISTORY_TAG)) {
          handleRunInRunHistory(element, context);
        }
        break;
      case XmlConstants.SUMMARY_TAG:
        if (parentIs(context.tagStack, XmlConstants.RESULT_TAG)) {
          handleSummary(element, context);
        }
        break;
      case XmlConstants.MODULE_TAG:
        if (parentIs(context.tagStack, XmlConstants.RESULT_TAG)) {
          handleModule(element, context);
        }
        break;
      case XmlConstants.MODULES_NOT_DONE_REASON:
        if (parentIs(context.tagStack, XmlConstants.MODULE_TAG)) {
          handleModuleReason(element, context);
        }
        break;
      case XmlConstants.CASE_TAG:
        if (parentIs(context.tagStack, XmlConstants.MODULE_TAG)) {
          handleTestCase(element, context);
        }
        break;
      case XmlConstants.TEST_TAG:
        if (parentIs(context.tagStack, XmlConstants.CASE_TAG)) {
          handleTest(element, context);
        }
        break;
      case XmlConstants.FAILURE_TAG:
        if (parentIs(context.tagStack, XmlConstants.TEST_TAG)) {
          handleTestFailure(element, context);
        }
        break;
      case XmlConstants.STACKTRACE_TAG:
        if (parentIs(context.tagStack, XmlConstants.FAILURE_TAG)) {
          handleTestFailureStackTrace(context);
        }
        break;
      case XmlConstants.BUGREPORT_TAG:
      case XmlConstants.LOGCAT_TAG:
      case XmlConstants.SCREENSHOT_TAG:
        if (parentIs(context.tagStack, XmlConstants.TEST_TAG)) {
          handleLoggedFile(element, context);
        }
        break;
      case XmlConstants.METRIC_TAG:
        if (parentIs(context.tagStack, XmlConstants.TEST_TAG)) {
          handleMetric(element, context);
        }
        break;
      default: // fall out
    }
  }

  private static void exitingTag(EndElement element, Context context) {
    context.tagStack.pop();
    String elementName = element.getName().getLocalPart();
    switch (elementName) {
      case XmlConstants.RUN_HISTORY_TAG:
        handleEndRunHistory(context);
        break;
      case XmlConstants.MODULE_TAG:
        handleEndModule(context);
        break;
      case XmlConstants.CASE_TAG:
        handleEndTestCase(context);
        break;
      case XmlConstants.TEST_TAG:
        handleEndTest(context);
        break;
      case XmlConstants.FAILURE_TAG:
        handleEndTestFailure(context);
        break;
      case XmlConstants.STACKTRACE_TAG:
        handleEndTestFailureStackTrace(context);
        break;
      case XmlConstants.BUGREPORT_TAG:
      case XmlConstants.LOGCAT_TAG:
      case XmlConstants.SCREENSHOT_TAG:
        handleEndLoggedFile(elementName, context);
        break;
      case XmlConstants.METRIC_TAG:
        handleEndMetric(context);
        break;
      default: // fall out
    }
  }

  private static void characters(Characters element, Context context) {
    if (element.isWhiteSpace()) {
      return;
    }
    StartElement tag = context.tagStack.peek();
    String tagName = tag.getName().getLocalPart();
    switch (tagName) {
      case XmlConstants.STACKTRACE_TAG:
      case XmlConstants.BUGREPORT_TAG:
      case XmlConstants.LOGCAT_TAG:
      case XmlConstants.SCREENSHOT_TAG:
      case XmlConstants.METRIC_TAG:
        handleElementTextContent(tagName, element, context);
        break;
      default: // fall out
    }
  }

  private static void handleResult(StartElement result, Context context) {
    Map<String, String> attributeMap = getAttributeMap(result);
    ImmutableList.Builder<Attribute> attributes = ImmutableList.builder();
    // Ensures attributes start, end, start_display, end_display are showed at the beginning.
    if (attributeMap.containsKey(XmlConstants.START_TIME_ATTR)) {
      attributes.add(
          Attribute.newBuilder()
              .setKey(XmlConstants.START_TIME_ATTR)
              .setValue(attributeMap.get(XmlConstants.START_TIME_ATTR).trim())
              .build());
      attributeMap.remove(XmlConstants.START_TIME_ATTR);
    }
    if (attributeMap.containsKey(XmlConstants.END_TIME_ATTR)) {
      attributes.add(
          Attribute.newBuilder()
              .setKey(XmlConstants.END_TIME_ATTR)
              .setValue(attributeMap.get(XmlConstants.END_TIME_ATTR).trim())
              .build());
      attributeMap.remove(XmlConstants.END_TIME_ATTR);
    }
    if (attributeMap.containsKey(XmlConstants.START_DISPLAY_TIME_ATTR)) {
      attributes.add(
          Attribute.newBuilder()
              .setKey(XmlConstants.START_DISPLAY_TIME_ATTR)
              .setValue(attributeMap.get(XmlConstants.START_DISPLAY_TIME_ATTR).trim())
              .build());
      attributeMap.remove(XmlConstants.START_DISPLAY_TIME_ATTR);
    }
    if (attributeMap.containsKey(XmlConstants.END_DISPLAY_TIME_ATTR)) {
      attributes.add(
          Attribute.newBuilder()
              .setKey(XmlConstants.END_DISPLAY_TIME_ATTR)
              .setValue(attributeMap.get(XmlConstants.END_DISPLAY_TIME_ATTR).trim())
              .build());
      attributeMap.remove(XmlConstants.END_DISPLAY_TIME_ATTR);
    }

    // Appends the rest of attributes
    attributeMap.entrySet().stream()
        .sorted(comparingByKey())
        .map(
            attr ->
                Attribute.newBuilder()
                    .setKey(attr.getKey())
                    .setValue(attr.getValue().trim())
                    .build())
        .forEach(attributes::add);

    context.resultBuilder.addAllAttribute(attributes.build());
  }

  private static void handleBuildInfo(StartElement buildInfo, Context context) {
    // <Build> is a self-closing tag, update the parent when entering this tag.
    BuildInfo.Builder build = BuildInfo.newBuilder();
    Map<String, String> attributeMap = getAttributeMap(buildInfo);
    if (attributeMap.containsKey(XmlConstants.BUILD_FINGERPRINT_ATTR)) {
      build.setBuildFingerprint(attributeMap.get(XmlConstants.BUILD_FINGERPRINT_ATTR).trim());
    }

    ImmutableList<Attribute> buildAttributes =
        attributeMap.entrySet().stream()
            .filter(e -> e.getKey().startsWith("build_"))
            .sorted(comparingByKey())
            .map(
                attr ->
                    Attribute.newBuilder().setKey(attr.getKey()).setValue(attr.getValue()).build())
            .collect(toImmutableList());

    ImmutableList<Attribute> nonBuildAttributes =
        attributeMap.entrySet().stream()
            .filter(e -> !e.getKey().startsWith("build_"))
            .sorted(comparingByKey())
            .map(
                attr ->
                    Attribute.newBuilder().setKey(attr.getKey()).setValue(attr.getValue()).build())
            .collect(toImmutableList());

    build.addAllAttribute(nonBuildAttributes).addAllAttribute(buildAttributes);
    context.resultBuilder.setBuild(build.build());
  }

  private static void handleRunHistory(Context context) {
    context.currentRunHistory = RunHistory.newBuilder();
  }

  private static void handleRunInRunHistory(StartElement run, Context context) {
    // <Run> is a self-closing tag, update the parent when entering this tag.
    Run.Builder runBuilder = Run.newBuilder();

    Map<String, String> attributeMap = getAttributeMap(run);

    if (attributeMap.containsKey(XmlConstants.START_TIME_ATTR)) {
      runBuilder.setStartTimeMillis(
          Long.parseLong(attributeMap.get(XmlConstants.START_TIME_ATTR).trim()));
    }
    if (attributeMap.containsKey(XmlConstants.END_TIME_ATTR)) {
      runBuilder.setEndTimeMillis(
          Long.parseLong(attributeMap.get(XmlConstants.END_TIME_ATTR).trim()));
    }
    if (attributeMap.containsKey(XmlConstants.PASS_ATTR)) {
      runBuilder.setPassedTests(Long.parseLong(attributeMap.get(XmlConstants.PASS_ATTR).trim()));
    }
    if (attributeMap.containsKey(XmlConstants.FAILED_ATTR)) {
      runBuilder.setFailedTests(Long.parseLong(attributeMap.get(XmlConstants.FAILED_ATTR).trim()));
    }

    if (attributeMap.containsKey(XmlConstants.COMMAND_LINE_ARGS)) {
      runBuilder.setCommandLineArgs(attributeMap.get(XmlConstants.COMMAND_LINE_ARGS).trim());
    }
    if (attributeMap.containsKey(XmlConstants.HOST_NAME_ATTR)) {
      runBuilder.setHostName(attributeMap.get(XmlConstants.HOST_NAME_ATTR).trim());
    }

    context.currentRunHistory.addRun(runBuilder.build());
  }

  private static void handleSummary(StartElement summary, Context context) {
    // <Summary> is a self-closing tag, update the parent when entering this tag.
    Summary.Builder summaryBuilder = Summary.newBuilder();

    Map<String, String> attributeMap = getAttributeMap(summary);

    if (attributeMap.containsKey(XmlConstants.PASS_ATTR)) {
      summaryBuilder.setPassed(Integer.parseInt(attributeMap.get(XmlConstants.PASS_ATTR).trim()));
    }
    if (attributeMap.containsKey(XmlConstants.FAILED_ATTR)) {
      summaryBuilder.setFailed(Integer.parseInt(attributeMap.get(XmlConstants.FAILED_ATTR).trim()));
    }
    if (attributeMap.containsKey(XmlConstants.MODULES_DONE_ATTR)) {
      summaryBuilder.setModulesDone(
          Integer.parseInt(attributeMap.get(XmlConstants.MODULES_DONE_ATTR).trim()));
    }
    if (attributeMap.containsKey(XmlConstants.MODULES_TOTAL_ATTR)) {
      summaryBuilder.setModulesTotal(
          Integer.parseInt(attributeMap.get(XmlConstants.MODULES_TOTAL_ATTR).trim()));
    }
    context.resultBuilder.setSummary(summaryBuilder.build());
  }

  private static void handleModule(StartElement module, Context context) {
    context.currentModule = Module.newBuilder();

    Map<String, String> attributeMap = getAttributeMap(module);

    if (attributeMap.containsKey(XmlConstants.NAME_ATTR)) {
      context.currentModule.setName(attributeMap.get(XmlConstants.NAME_ATTR).trim());
    }
    if (attributeMap.containsKey(XmlConstants.ABI_ATTR)) {
      context.currentModule.setAbi(attributeMap.get(XmlConstants.ABI_ATTR).trim());
    }
    if (attributeMap.containsKey(XmlConstants.RUNTIME_ATTR)) {
      context.currentModule.setRuntimeMillis(
          Long.parseLong(attributeMap.get(XmlConstants.RUNTIME_ATTR)));
    }
    if (attributeMap.containsKey(XmlConstants.DONE_ATTR)) {
      context.currentModule.setDone(
          Boolean.parseBoolean(attributeMap.get(XmlConstants.DONE_ATTR).trim()));
    }
    if (attributeMap.containsKey(XmlConstants.PASS_ATTR)) {
      context.currentModule.setPassed(
          Integer.parseInt(attributeMap.get(XmlConstants.PASS_ATTR).trim()));
    }
    if (attributeMap.containsKey(XmlConstants.TOTAL_TESTS_ATTR)) {
      context.currentModule.setTotalTests(
          Integer.parseInt(attributeMap.get(XmlConstants.TOTAL_TESTS_ATTR).trim()));
    }
  }

  private static void handleModuleReason(StartElement moduleReason, Context context) {
    // <Reason> is a self-closing tag, update the parent when entering this tag.
    Reason.Builder reason = Reason.newBuilder();

    Map<String, String> attributeMap = getAttributeMap(moduleReason);

    if (attributeMap.containsKey(XmlConstants.MESSAGE_ATTR)) {
      reason.setMsg(attributeMap.get(XmlConstants.MESSAGE_ATTR).trim());
    }
    if (attributeMap.containsKey(XmlConstants.ERROR_NAME_ATTR)) {
      reason.setErrorName(attributeMap.get(XmlConstants.ERROR_NAME_ATTR).trim());
    }
    if (attributeMap.containsKey(XmlConstants.ERROR_CODE_ATTR)) {
      reason.setErrorCode(attributeMap.get(XmlConstants.ERROR_CODE_ATTR).trim());
    }
    context.currentModule.setReason(reason.build());
  }

  private static void handleTestCase(StartElement testCase, Context context) {
    context.currentTestCase = TestCase.newBuilder();

    Map<String, String> attributeMap = getAttributeMap(testCase);

    if (attributeMap.containsKey(XmlConstants.NAME_ATTR)) {
      context.currentTestCase.setName(attributeMap.get(XmlConstants.NAME_ATTR).trim());
    }
  }

  private static void handleTest(StartElement test, Context context) {
    context.currentTest = Test.newBuilder();
    Map<String, String> attributeMap = getAttributeMap(test);

    if (attributeMap.containsKey(XmlConstants.RESULT_ATTR)) {
      context.currentTest.setResult(attributeMap.get(XmlConstants.RESULT_ATTR).trim());
    }
    if (attributeMap.containsKey(XmlConstants.NAME_ATTR)) {
      context.currentTest.setName(attributeMap.get(XmlConstants.NAME_ATTR).trim());
    }
    if (attributeMap.containsKey(XmlConstants.SKIPPED_ATTR)) {
      context.currentTest.setSkipped(
          Boolean.parseBoolean(attributeMap.get(XmlConstants.SKIPPED_ATTR).trim()));
    }
  }

  private static void handleTestFailure(StartElement testFailure, Context context) {
    context.currentTestFailure = TestFailure.newBuilder();
    Map<String, String> attributeMap = getAttributeMap(testFailure);

    if (attributeMap.containsKey(XmlConstants.MESSAGE_ATTR)) {
      context.currentTestFailure.setMsg(attributeMap.get(XmlConstants.MESSAGE_ATTR).trim());
    }
    if (attributeMap.containsKey(XmlConstants.ERROR_NAME_ATTR)) {
      context.currentTestFailure.setErrorName(
          attributeMap.get(XmlConstants.ERROR_NAME_ATTR).trim());
    }
    if (attributeMap.containsKey(XmlConstants.ERROR_CODE_ATTR)) {
      context.currentTestFailure.setErrorCode(
          attributeMap.get(XmlConstants.ERROR_CODE_ATTR).trim());
    }
  }

  private static void handleTestFailureStackTrace(Context context) {
    context.currentStackTrace = StackTrace.newBuilder();
  }

  private static void handleLoggedFile(StartElement loggedFile, Context context) {
    Map<String, String> attributeMap = getAttributeMap(loggedFile);
    String elementName = loggedFile.getName().getLocalPart();
    if (attributeMap.containsKey(XmlConstants.LOG_FILE_NAME_ATTR)) {
      switch (elementName) {
        case XmlConstants.BUGREPORT_TAG:
          context.currentBugReport = LoggedFile.newBuilder();
          context.currentBugReport.setFileName(
              attributeMap.get(XmlConstants.LOG_FILE_NAME_ATTR).trim());
          break;
        case XmlConstants.LOGCAT_TAG:
          context.currentLogcat = LoggedFile.newBuilder();
          context.currentLogcat.setFileName(
              attributeMap.get(XmlConstants.LOG_FILE_NAME_ATTR).trim());
          break;
        case XmlConstants.SCREENSHOT_TAG:
          context.currentScreenshot = LoggedFile.newBuilder();
          context.currentScreenshot.setFileName(
              attributeMap.get(XmlConstants.LOG_FILE_NAME_ATTR).trim());
          break;
        default: // fall out
      }
    }
  }

  private static void handleMetric(StartElement metric, Context context) {
    context.currentMetric = Metric.newBuilder();
    Map<String, String> attributeMap = getAttributeMap(metric);

    if (attributeMap.containsKey(XmlConstants.METRIC_KEY)) {
      context.currentMetric.setKey(attributeMap.get(XmlConstants.METRIC_KEY).trim());
    }
  }

  private static void handleEndRunHistory(Context context) {
    if (context.currentRunHistory != null) {
      context.resultBuilder.setRunHistory(context.currentRunHistory.build());
      context.currentRunHistory = null;
    }
  }

  private static void handleEndModule(Context context) {
    if (context.currentModule != null) {
      context.resultBuilder.addModuleInfo(context.currentModule.build());
      context.currentModule = null;
    }
  }

  private static void handleEndTestCase(Context context) {
    if (context.currentTestCase != null) {
      context.currentModule.addTestCase(context.currentTestCase.build());
      context.currentTestCase = null;
    }
  }

  private static void handleEndTest(Context context) {
    if (context.currentTest != null) {
      context.currentTestCase.addTest(context.currentTest.build());
      context.currentTest = null;
    }
  }

  private static void handleEndTestFailure(Context context) {
    if (context.currentTestFailure != null) {
      context.currentTest.setFailure(context.currentTestFailure.build());
      context.currentTestFailure = null;
    }
  }

  private static void handleEndTestFailureStackTrace(Context context) {
    if (context.currentStackTrace != null) {
      context.currentTestFailure.setStackTrace(context.currentStackTrace.build());
      context.currentStackTrace = null;
    }
  }

  private static void handleEndLoggedFile(String elementName, Context context) {
    switch (elementName) {
      case XmlConstants.BUGREPORT_TAG:
        if (context.currentBugReport != null) {
          context.currentTest.setBugReport(context.currentBugReport.build());
          context.currentBugReport = null;
        }
        break;
      case XmlConstants.LOGCAT_TAG:
        if (context.currentLogcat != null) {
          context.currentTest.setLogcat(context.currentLogcat.build());
          context.currentLogcat = null;
        }
        break;
      case XmlConstants.SCREENSHOT_TAG:
        if (context.currentScreenshot != null) {
          context.currentTest.addScreenshot(context.currentScreenshot.build());
          context.currentScreenshot = null;
        }
        break;
      default: // fall out
    }
  }

  private static void handleEndMetric(Context context) {
    if (context.currentMetric != null) {
      context.currentTest.addMetric(context.currentMetric.build());
      context.currentMetric = null;
    }
  }

  private static void handleElementTextContent(
      String elementName, Characters content, Context context) {
    if (content.isWhiteSpace()) {
      return;
    }
    String textContent = content.getData().trim();
    switch (elementName) {
      case XmlConstants.STACKTRACE_TAG:
        context.currentStackTrace.setContent(textContent);
        break;
      case XmlConstants.BUGREPORT_TAG:
        context.currentBugReport.setContent(textContent);
        break;
      case XmlConstants.LOGCAT_TAG:
        context.currentLogcat.setContent(textContent);
        break;
      case XmlConstants.SCREENSHOT_TAG:
        context.currentScreenshot.setContent(textContent);
        break;
      case XmlConstants.METRIC_TAG:
        context.currentMetric.setContent(textContent);
        break;
      default: // fall out
    }
  }

  /** Gets the attribute map. */
  private static Map<String, String> getAttributeMap(StartElement element) {
    @SuppressWarnings("rawtypes")
    Iterator attributesIt = element.getAttributes(); // Attributes order is random
    Map<String, String> attributeMap = Maps.newLinkedHashMap();
    javax.xml.stream.events.Attribute attribute;
    while (attributesIt.hasNext()) {
      attribute = (javax.xml.stream.events.Attribute) attributesIt.next();
      attributeMap.put(attribute.getName().toString(), attribute.getValue());
    }
    return attributeMap;
  }

  /**
   * Returns {@code true} if parentElementName is the second most recent item.
   *
   * @param stack The stack to check.
   * @param parentElementName The name of the expected parent element. Or null if no parent
   *     expected.
   */
  private static boolean parentIs(Deque<StartElement> stack, @Nullable String parentElementName) {
    if (parentElementName == null) {
      return stack.isEmpty();
    }

    StartElement current = stack.poll();
    StartElement parent = stack.peek();
    boolean parentMatch =
        parent != null && parentElementName.equals(parent.getName().getLocalPart());
    stack.push(current);
    return parentMatch;
  }
}
