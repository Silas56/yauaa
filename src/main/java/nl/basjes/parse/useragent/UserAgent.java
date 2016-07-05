/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2016 Niels Basjes
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package nl.basjes.parse.useragent;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;
import org.apache.commons.lang3.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserAgent extends UserAgentBaseListener implements ANTLRErrorListener {

    private static final Logger LOG = LoggerFactory.getLogger(UserAgent.class);
    public static final String DEVICE_CLASS = "DeviceClass";
    public static final String DEVICE_BRAND = "DeviceBrand";
    public static final String DEVICE_NAME = "DeviceName";
    public static final String OPERATING_SYSTEM_CLASS = "OperatingSystemClass";
    public static final String OPERATING_SYSTEM_NAME = "OperatingSystemName";
    public static final String OPERATING_SYSTEM_VERSION = "OperatingSystemVersion";
    public static final String LAYOUT_ENGINE_CLASS = "LayoutEngineClass";
    public static final String LAYOUT_ENGINE_NAME = "LayoutEngineName";
    public static final String LAYOUT_ENGINE_VERSION = "LayoutEngineVersion";
    public static final String LAYOUT_ENGINE_VERSION_MAJOR = "LayoutEngineVersionMajor";
    public static final String AGENT_CLASS = "AgentClass";
    public static final String AGENT_NAME = "AgentName";
    public static final String AGENT_VERSION = "AgentVersion";
    public static final String AGENT_VERSION_MAJOR = "AgentVersionMajor";

    public static final String SYNTAX_ERROR = "__SyntaxError__";

    public static final String[] STANDARD_FIELDS = {
        DEVICE_CLASS,
//        DEVICE_BRAND,
        DEVICE_NAME,
        OPERATING_SYSTEM_CLASS,
        OPERATING_SYSTEM_NAME,
        OPERATING_SYSTEM_VERSION,
        LAYOUT_ENGINE_CLASS,
        LAYOUT_ENGINE_NAME,
        LAYOUT_ENGINE_VERSION,
        LAYOUT_ENGINE_VERSION_MAJOR,
        AGENT_CLASS,
        AGENT_NAME,
        AGENT_VERSION,
        AGENT_VERSION_MAJOR
    };

    boolean hasSyntaxError;
    boolean hasAmbiguity;

    public boolean hasSyntaxError() {
        return hasSyntaxError;
    }

    public boolean hasAmbiguity() {
        return hasAmbiguity;
    }

    @Override
    public void syntaxError(
            Recognizer<?, ?> recognizer,
            Object offendingSymbol,
            int line,
            int charPositionInLine,
            String msg,
            RecognitionException e) {
        if (debug) {
            LOG.error("Syntax error");
            LOG.error("Source : {}", userAgentString);
            LOG.error("Message: {}", msg);
        }
        hasSyntaxError = true;
        AgentField syntaxError = new AgentField("false");
        syntaxError.setValue("true", 1);
        allFields.put(SYNTAX_ERROR, syntaxError);
    }

    @Override
    public void reportAmbiguity(
            Parser recognizer,
            DFA dfa,
            int startIndex,
            int stopIndex,
            boolean exact,
            BitSet ambigAlts,
            ATNConfigSet configs) {
        hasAmbiguity = true;
//        allFields.put("__Ambiguity__",new AgentField("true"));
    }

    @Override
    public void reportAttemptingFullContext(
            Parser recognizer,
            DFA dfa,
            int startIndex,
            int stopIndex,
            BitSet conflictingAlts,
            ATNConfigSet configs) {
    }

    @Override
    public void reportContextSensitivity(
            Parser recognizer,
            DFA dfa,
            int startIndex,
            int stopIndex,
            int prediction,
            ATNConfigSet configs) {

    }

    // The original input value
    private String userAgentString = null;

    private boolean debug = false;

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean newDebug) {
        this.debug = newDebug;
    }

    class AgentField {
        String defaultValue;
        String value;
        long confidence;

        AgentField(String defaultValue) {
            this.defaultValue = defaultValue;
            reset();
        }

        public void reset() {
            value = defaultValue;
            confidence = -1;
        }

        public String getValue() {
            return value;
        }

        public boolean setValue(AgentField field) {
            return setValue(field.value, field.confidence);
        }

        public boolean setValue(String newValue, long newConfidence) {
            if (newConfidence > this.confidence) {
                this.confidence = newConfidence;
                this.value = newValue;
                return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return ">" + this.value + "#" + this.confidence + "<";
        }
    }

    private Map<String, AgentField> allFields = new HashMap<>(32);


    public UserAgent() {
        init();
    }

    public UserAgent(String userAgentString) {
        init();
        setUserAgentString(userAgentString);
    }

    public UserAgent(UserAgent userAgent) {
        clone(userAgent);
    }

    public void clone(UserAgent userAgent) {
        init();
        setUserAgentString(userAgentString);
        for (Map.Entry<String, AgentField> entry : userAgent.allFields.entrySet()) {
            set(entry.getKey(), entry.getValue().getValue(), entry.getValue().confidence);
        }
    }

    private void init() {
        // Device : Family - Brand - Model
        allFields.put(DEVICE_CLASS,                  new AgentField("Unknown")); // Hacker / Cloud / Server / Desktop / Tablet / Phone / Watch
        allFields.put(DEVICE_BRAND,                  new AgentField("Unknown")); // (Google/AWS/Asure) / ????
        allFields.put(DEVICE_NAME,                   new AgentField("Unknown")); // (Google/AWS/Asure) / ????

        // Operating system
        allFields.put(OPERATING_SYSTEM_CLASS,        new AgentField("Unknown")); // Cloud, Desktop, Mobile, Embedded
        allFields.put(OPERATING_SYSTEM_NAME,         new AgentField("Unknown")); // ( Linux / Android / Windows ...)
        allFields.put(OPERATING_SYSTEM_VERSION,      new AgentField("Unknown")); // 1.2 / 43 / ...

        // Engine : Class (=None/Hacker/Robot/Browser) - Name - Version
        allFields.put(LAYOUT_ENGINE_CLASS,           new AgentField("Unknown")); // None / Hacker / Robot / Browser /
        allFields.put(LAYOUT_ENGINE_NAME,            new AgentField("Unknown")); // ( GoogleBot / Bing / ...) / (Trident / Gecko / ...)
        allFields.put(LAYOUT_ENGINE_VERSION,         new AgentField("Unknown")); // 1.2 / 43 / ...
        allFields.put(LAYOUT_ENGINE_VERSION_MAJOR,   new AgentField("Unknown")); // 1 / 43 / ...

        // Agent: Class (=Hacker/Robot/Browser) - Name - Version
        allFields.put(AGENT_CLASS,                   new AgentField("Unknown")); // Hacker / Robot / Browser /
        allFields.put(AGENT_NAME,                    new AgentField("Unknown")); // ( GoogleBot / Bing / ...) / ( Firefox / Chrome / ... )
        allFields.put(AGENT_VERSION,                 new AgentField("Unknown")); // 1.2 / 43 / ...
        allFields.put(AGENT_VERSION_MAJOR,           new AgentField("Unknown")); // 1 / 43 / ...
    }

    public void setUserAgentString(String newUserAgentString) {
        this.userAgentString = newUserAgentString;
        reset();
    }

    public String getUserAgentString() {
        return userAgentString;
    }

    public void reset() {
        hasSyntaxError = false;
        hasAmbiguity = false;

        for (AgentField field : allFields.values()) {
            field.reset();
        }
    }

    public void set(String attribute, String value, long confidence) {
        AgentField field = allFields.get(attribute);
        if (field == null) {
            field = new AgentField(null); // The fields we do not know get a 'null' default
        }

        boolean wasEmpty = confidence == -1;
        boolean updated = field.setValue(value, confidence);
        if (debug && !wasEmpty) {
            if (updated) {
                LOG.info("USE  {} ({}) = {}", attribute, confidence, value);
            } else {
                LOG.info("SKIP {} ({}) = {}", attribute, confidence, value);
            }
        }
        allFields.put(attribute, field);
    }

    public void set(UserAgent newValuesUserAgent) {
        for (String fieldName : newValuesUserAgent.allFields.keySet()) {
            set(fieldName, newValuesUserAgent.allFields.get(fieldName));
        }
    }

    private void set(String fieldName, AgentField agentField) {
        set(fieldName, agentField.value, agentField.confidence);
    }

    public AgentField get(String fieldName) {
        return allFields.get(fieldName);
    }

    public String getValue(String fieldName) {
        AgentField field = allFields.get(fieldName);
        if (field == null) {
            return null;
        }
        return field.getValue();
    }

    public String getConfidence(String fieldName) {
        AgentField field = allFields.get(fieldName);
        if (field == null) {
            return null;
        }
        return field.getValue();
    }

    public String toYamlTestCase() {
        StringBuilder sb = new StringBuilder(10240);
        sb.append("\n");
        sb.append("  - test:\n");
        sb.append("#      options:\n");
        sb.append("#        - 'verbose'\n");
        sb.append("#        - 'init'\n");
        sb.append("#        - 'only'\n");
        sb.append("      input:\n");
        sb.append("#        name: 'You can give the test case a name'\n");
        sb.append("        user_agent_string: '").append(userAgentString).append("'\n");
        sb.append("      expected:\n");

        List<String> fieldNames = getAvailableFieldNamesSorted();

        int maxNameLength = 0;
        int maxValueLength = 0;
        for (String fieldName : allFields.keySet()) {
            maxNameLength = Math.max(maxNameLength, fieldName.length());
        }
        for (String fieldName : fieldNames) {
            maxValueLength = Math.max(maxValueLength, get(fieldName).getValue().length());
        }

        for (String fieldName : fieldNames) {
            sb.append("        ").append(fieldName);
            for (int l = fieldName.length(); l < maxNameLength + 5; l++) {
                sb.append(' ');
            }
            String value = get(fieldName).getValue();
            sb.append(": '").append(value).append('\'');
            for (int l = value.length(); l < maxValueLength + 5; l++) {
                sb.append(' ');
            }
            sb.append("# ").append(get(fieldName).confidence);
            sb.append('\n');
        }
        sb.append("\n");
        sb.append("\n");

        return sb.toString();
    }


//    {
//        "agent": {
//            "user_agent_string": "Mozilla/5.0 (iPhone; CPU iPhone OS 9_2_1 like Mac OS X) AppleWebKit/601.1.46
//                                  (KHTML, like Gecko) Version/9.0 Mobile/13D15 Safari/601.1"
//            "AgentClass": "Browser",
//            "AgentName": "Safari",
//            "AgentVersion": "9.0",
//            "DeviceBrand": "Apple",
//            "DeviceClass": "Phone",
//            "DeviceFirmwareVersion": "13D15",
//            "DeviceName": "iPhone",
//            "LayoutEngineClass": "Browser",
//            "LayoutEngineName": "AppleWebKit",
//            "LayoutEngineVersion": "601.1.46",
//            "OperatingSystemClass": "Mobile",
//            "OperatingSystemName": "iOS",
//            "OperatingSystemVersion": "9_2_1",
//        }
//    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(10240);
        sb.append("{");

        List<String> fieldNames = getAvailableFieldNames();
        Collections.sort(fieldNames);

        for (String fieldName : UserAgent.STANDARD_FIELDS) {
            fieldNames.remove(fieldName);
            sb
                .append('"').append(StringEscapeUtils.escapeJson(fieldName))                .append('"')
                .append(':')
                .append('"').append(StringEscapeUtils.escapeJson(get(fieldName).getValue())).append('"')
                .append(',');
        }
        for (String fieldName : fieldNames) {
            sb
                .append('"').append(StringEscapeUtils.escapeJson(fieldName))                .append('"')
                .append(':')
                .append('"').append(StringEscapeUtils.escapeJson(get(fieldName).getValue())).append('"')
                .append(',');
        }
        sb
            .append("\"user_agent_string\":")
            .append('"').append(StringEscapeUtils.escapeJson(userAgentString)).append('"')
            .append("}\n");
        return sb.toString();
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("  - user_agent_string: '\"" + userAgentString + "\"'\n");
        int maxLength = 0;
        for (String fieldName : allFields.keySet()) {
            maxLength = Math.max(maxLength, fieldName.length());
        }
        for (String fieldName : allFields.keySet()) {
            AgentField field = allFields.get(fieldName);
            if (field.getValue() != null) {
                sb.append("    ").append(fieldName);
                for (int l = fieldName.length(); l< maxLength+2; l++) {
                    sb.append(' ');
                }
                sb.append(": '").append(field.getValue()).append('\'');
                sb.append("# ").append(field.confidence);
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public List<String> getAvailableFieldNames() {
        List<String> resultSet = new ArrayList<>(allFields.size());
        for (String fieldName : allFields.keySet()) {
            AgentField field = allFields.get(fieldName);
            if (field.confidence >= 0 && field.getValue() != null) {
                resultSet.add(fieldName);
            }
        }
        return resultSet;
    }

    public List<String> getAvailableFieldNamesSorted() {
        List<String> fieldNames = new ArrayList<>(getAvailableFieldNames());
        Collections.sort(fieldNames);

        List<String> result = new ArrayList<>();
        for (String fieldName : STANDARD_FIELDS) {
            fieldNames.remove(fieldName);
            result.add(fieldName);
        }
        for (String fieldName : fieldNames) {
            result.add(fieldName);
        }
        return result;

    }


}