/*
 * Copyright (c) 2015, the Dart project authors.
 *
 * Licensed under the Eclipse Public License v1.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.dartlang.vm.service.element;

// This file is generated by the script: pkg/vm_service/tool/generate.dart in dart-lang/sdk.

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A {@link BoundField} represents a field bound to a particular value in an {@link Instance}.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class BoundField extends Element {

  public BoundField(JsonObject json) {
    super(json);
  }

  /**
   * Provided for fields of instances that are NOT of the following instance kinds:
   *  - Record
   *
   * Note: this property is deprecated and will be replaced by `name`.
   */
  public FieldRef getDecl() {
    return new FieldRef((JsonObject) json.get("decl"));
  }

  /**
   * @return one of <code>String</code> or <code>int</code>
   */
  public Object getName() {
    final JsonElement elem = json.get("name");
    if (elem == null) return null;

    if (elem.isJsonObject()) return elem.getAsString();
    if (elem.isJsonPrimitive()) {
      try {
        return elem.getAsInt();
      } catch (NumberFormatException ex) {
        return elem.getAsString(); // e.g. name is "$1"
      }
    }
    return null;
  }

  /**
   * @return one of <code>InstanceRef</code> or <code>Sentinel</code>
   */
  public InstanceRef getValue() {
    final JsonElement elem = json.get("value");
    if (!elem.isJsonObject()) return null;
    final JsonObject child = elem.getAsJsonObject();
    final String type = child.get("type").getAsString();
    if ("Sentinel".equals(type)) return null;
    return new InstanceRef(child);
  }
}
