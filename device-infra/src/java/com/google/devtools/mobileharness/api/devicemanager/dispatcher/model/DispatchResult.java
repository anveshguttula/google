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

package com.google.devtools.mobileharness.api.devicemanager.dispatcher.model;

import com.google.auto.value.AutoValue;
import com.google.devtools.mobileharness.api.model.lab.DeviceId;
import com.google.wireless.qa.mobileharness.shared.api.device.Device;

/** The data model containing all information of a dispatch result. */
@AutoValue
public abstract class DispatchResult {
  public static DispatchResult of(
      DeviceId deviceId, DispatchType dispatchType, Class<? extends Device> deviceType) {
    return new AutoValue_DispatchResult(deviceId, dispatchType, deviceType);
  }

  /** The device DeviceId generated by dispatcher. */
  public abstract DeviceId deviceId();

  /** The dispatch type. */
  public abstract DispatchType dispatchType();

  /** The device class type. */
  public abstract Class<? extends Device> deviceType();

  /** The dispatch type enum of dispatch result. */
  public enum DispatchType {
    /** The result is from real time detection. */
    LIVE,
    /** The result is from cache. */
    CACHE,
    /** The result is marked as a sub device. */
    SUB_DEVICE,
    /** The result need be filtered, won't generate the device runner. */
    ONLY_FILTER,
  }
}
