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

package com.google.devtools.mobileharness.platform.android.app.binary.devicedaemon.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Broadcast receiver for starting {@link DaemonService} when device is booted. */
public class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent arg1) {
    Intent serviceIntent = new Intent(context, DaemonService.class);
    serviceIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startService(serviceIntent);

    Intent activityIntent = new Intent(context, DaemonActivity.class);
    activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(activityIntent);
  }
}
