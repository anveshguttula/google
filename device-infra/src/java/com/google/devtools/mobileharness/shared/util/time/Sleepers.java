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

package com.google.devtools.mobileharness.shared.util.time;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Verify;
import java.time.Duration;

/**
 * {@link Sleeper} implementations.
 *
 * <p>These are in a separate file because otherwise they would be publicly visible in the {@link
 * Sleeper} interface.
 */
final class Sleepers {

  enum DefaultSleeper implements Sleeper {
    INSTANCE;

    @Override
    @SuppressWarnings("GoodTime") // decomposition of Duration
    public void sleep(Duration duration) throws InterruptedException {
      checkArgument(!duration.isNegative(), "duration (%s) must not be negative", duration);
      try {
        long millis = duration.toMillis();

        Duration nanosOnly = duration.minusMillis(millis);
        // Calling getNano() without also calling getSeconds() looks "unsafe" in that we're only
        // reading the nanos field, but the seconds field should be zero'ed out since we've
        // subtracted away anything greater than 1 millisecond. We verify that assumption.
        Verify.verify(nanosOnly.getSeconds() == 0L);
        int nanos = nanosOnly.getNano();

        Thread.sleep(millis, nanos);
      } catch (ArithmeticException tooBig) {
        // We don't pass 999999 nanoseconds here, because there's a bug in Thread.sleep() where the
        // milliseconds value will overflow.
        Thread.sleep(Long.MAX_VALUE);
      }
    }

    @Override
    public String toString() {
      return "Sleeper.defaultSleeper()";
    }
  }

  enum NoOpSleeper implements Sleeper {
    INSTANCE;

    @Override
    public void sleep(Duration duration) throws InterruptedException {
      checkArgument(!duration.isNegative(), "duration (%s) must not be negative", duration);
    }

    @Override
    public String toString() {
      return "Sleeper.noOpSleeper()";
    }
  }

  private Sleepers() {}
}
