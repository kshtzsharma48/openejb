/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

TOMEE.ApplicationHelpPanel = function (cfg) {
    "use strict";

    var channel = cfg.channel;

    var elements = (function () {
        var divBodyUid = TOMEE.Sequence.next();
        var tpl = [
            '<div class="container-fluid">',
            '<div id="' + divBodyUid + '" class="row-fluid"/>',
            '</div>'
        ];

        //create the element
        var all = $(tpl.join(''));
        var body = all.find("#" + divBodyUid);
        return {
            all: all,
            body: body
        };
    })();

    return {
        getEl: function () {
            return elements.all;
        }
    };
};