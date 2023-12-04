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

package com.google.devtools.mobileharness.infra.controller.test.local.utp.common;

import com.google.devtools.mobileharness.api.model.job.in.Decorators;
import com.google.devtools.mobileharness.infra.controller.test.local.utp.proto.BasicFlowProto;
import com.google.devtools.mobileharness.infra.controller.test.local.utp.proto.BasicFlowProto.BasicFlow;
import com.google.devtools.mobileharness.infra.controller.test.local.utp.proto.BasicFlowProto.MobileHarnessDecoratorStack;
import com.google.devtools.mobileharness.infra.controller.test.local.utp.proto.BasicFlowProto.MobileHarnessDriver;
import com.google.wireless.qa.mobileharness.shared.model.job.TestInfo;
import com.google.wireless.qa.mobileharness.shared.model.job.in.SubDeviceSpec;
import java.util.stream.Collectors;

/**
 * Utility for generating {@linkplain
 * com.google.devtools.mobileharness.infra.controller.test.local.utp.proto.TestFlowProto.TestFlow
 * test flow} related protos.
 */
public class TestFlows {

  /** Gets the original {@linkplain BasicFlow test flow} of a MH test. */
  public static BasicFlow getOriginalFlow(TestInfo testInfo) {
    return BasicFlow.newBuilder()
        .setDriver(
            BasicFlowProto.Driver.newBuilder()
                .setMobileHarnessDriver(
                    MobileHarnessDriver.newBuilder()
                        .setDriverName(testInfo.jobInfo().type().getDriver())))
        .addAllDecoratorStack(
            testInfo.jobInfo().subDeviceSpecs().getAllSubDevices().stream()
                .map(SubDeviceSpec::decorators)
                .map(Decorators::getAll)
                .map(
                    decorators ->
                        MobileHarnessDecoratorStack.newBuilder()
                            .addAllDecoratorName(decorators)
                            .build())
                .collect(Collectors.toList()))
        .build();
  }

  private TestFlows() {}
}