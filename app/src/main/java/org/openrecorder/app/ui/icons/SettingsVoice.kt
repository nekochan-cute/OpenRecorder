/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openrecorder.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Rounded.SettingsVoice: ImageVector
    get() {
        if (_settingsVoice != null) {
            return _settingsVoice!!
        }
        _settingsVoice = materialIcon(name = "Rounded.SettingsVoice") {
            materialPath {
                moveTo(8.0f, 24.0f)
                lineTo(8.0f, 24.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveTo(7.0f, 23.55f, 7.45f, 24.0f, 8.0f, 24.0f)
                close()
                moveTo(12.0f, 24.0f)
                lineTo(12.0f, 24.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveTo(11.0f, 23.55f, 11.45f, 24.0f, 12.0f, 24.0f)
                close()
                moveTo(16.0f, 24.0f)
                lineTo(16.0f, 24.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveTo(15.0f, 23.55f, 15.45f, 24.0f, 16.0f, 24.0f)
                close()
                moveTo(9.0f, 10.0f)
                verticalLineTo(4.0f)
                curveToRelative(0.0f, -1.66f, 1.34f, -3.0f, 3.0f, -3.0f)
                reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 1.66f, -1.34f, 3.0f, -3.0f, 3.0f)
                reflectiveCurveTo(9.0f, 11.66f, 9.0f, 10.0f)
                close()
                moveTo(17.91f, 10.0f)
                curveToRelative(0.61f, 0.0f, 1.09f, 0.54f, 1.0f, 1.14f)
                curveToRelative(-0.49f, 3.0f, -2.89f, 5.34f, -5.91f, 5.78f)
                verticalLineTo(19.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(-2.08f)
                curveToRelative(-3.02f, -0.44f, -5.42f, -2.78f, -5.91f, -5.78f)
                curveToRelative(-0.1f, -0.6f, 0.39f, -1.14f, 1.0f, -1.14f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.49f, 0.0f, 0.9f, 0.36f, 0.98f, 0.85f)
                curveTo(7.48f, 13.21f, 9.53f, 15.0f, 12.0f, 15.0f)
                reflectiveCurveToRelative(4.52f, -1.79f, 4.93f, -4.15f)
                curveTo(17.01f, 10.36f, 17.42f, 10.0f, 17.91f, 10.0f)
                lineTo(17.91f, 10.0f)
                close()
            }
        }
        return _settingsVoice!!
    }

private var _settingsVoice: ImageVector? = null
