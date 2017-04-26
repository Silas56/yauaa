/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2017 Niels Basjes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.basjes.parse.useragent;

import nl.basjes.parse.useragent.analyze.*;
import nl.basjes.parse.useragent.parse.UserAgentTreeFlattener;
import nl.basjes.parse.useragent.utils.Normalize;
import nl.basjes.parse.useragent.utils.VersionSplitter;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.commons.collections4.map.LRUMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static nl.basjes.parse.useragent.UserAgent.*;

public class UserAgentAnalyzer2 extends Analyzer {

    private static final int INFORM_ACTIONS_HASHMAP_SIZE = 300000;
    private static final int DEFAULT_PARSE_CACHE_SIZE = 10000;

    private static final Logger LOG = LoggerFactory.getLogger(UserAgentAnalyzer2.class);
    protected List<Matcher>                     allMatchers             = new ArrayList<>();
    private Map<String, Set<MatcherAction>>     informMatcherActions    = new HashMap<>(INFORM_ACTIONS_HASHMAP_SIZE);

    protected UserAgentTreeFlattener flattener;

    private UserAgentResource userAgentResource;

    public UserAgentAnalyzer2(UserAgentResource userAgentResource) {
       this.userAgentResource = userAgentResource;

        init();

    }

    private void init(){

  //      LOG.info("Building all init");

        flattener = new UserAgentTreeFlattener(this);

        final Map<String, Set<MatcherAction>> informMatcherActions = userAgentResource.getInformMatcherActions();
        this.informMatcherActions.putAll(informMatcherActions);

        List<Matcher> allMatchers = userAgentResource.getAllMatchers();
        for (Matcher matcher:allMatchers){
            Matcher cloneMatcher = matcher.Clone(this);
            this.allMatchers.add(cloneMatcher);
        }

    }

    private static String padding(char letter, int count) {
        StringBuilder sb = new StringBuilder(128);
        for (int i=0; i <count; i++) {
            sb.append(letter);
        }
        return sb.toString();
    }

    private static void logLine(String line, int width) {
        LOG.info("| {}{} |", line, padding(' ', width - line.length()));
    }

    // --------------------------------------------


    public static String getVersion() {
        return "Yauaa " + Version.getProjectVersion() + " (" + Version.getGitCommitIdDescribeShort() + " @ " + Version.getBuildTimestamp() + ")";
    }



    public Set<String> getAllPossibleFieldNames() {
        Set<String> results = new TreeSet<>();
        results.addAll(UserAgentResource.HARD_CODED_GENERATED_FIELDS);
        for (Matcher matcher: allMatchers) {
            results.addAll(matcher.getAllPossibleFieldNames());
        }
        return results;
    }

    public List<String> getAllPossibleFieldNamesSorted() {
        List<String> fieldNames = new ArrayList<>(getAllPossibleFieldNames());
        Collections.sort(fieldNames);

        List<String> result = new ArrayList<>();
        for (String fieldName : PRE_SORTED_FIELDS_LIST) {
            fieldNames.remove(fieldName);
            result.add(fieldName);
        }
        for (String fieldName : fieldNames) {
            result.add(fieldName);
        }
        return result;
    }


/*
Example of the structure of the yaml file:
----------------------------
config:
  - lookup:
    name: 'lookupname'
    map:
        "From1" : "To1"
        "From2" : "To2"
        "From3" : "To3"
  - matcher:
      options:
        - 'verbose'
        - 'init'
      require:
        - 'Require pattern'
        - 'Require pattern'
      extract:
        - 'Extract pattern'
        - 'Extract pattern'
  - test:
      input:
        user_agent_string: 'Useragent'
      expected:
        FieldName     : 'ExpectedValue'
        FieldName     : 'ExpectedValue'
        FieldName     : 'ExpectedValue'
----------------------------
*/

    public void informMeAbout(MatcherAction matcherAction, String keyPattern) {
        String hashKey = keyPattern.toLowerCase();
        Set<MatcherAction> analyzerSet = informMatcherActions.get(hashKey);
        if (analyzerSet == null) {
            analyzerSet = new HashSet<>();
            informMatcherActions.put(hashKey, analyzerSet);
        }
        analyzerSet.add(matcherAction);
    }

    private boolean verbose = false;

    public void setVerbose(boolean newVerbose) {
        this.verbose = newVerbose;
        flattener.setVerbose(newVerbose);
    }

    public UserAgent parse(String userAgentString) {
        UserAgent userAgent = new UserAgent(userAgentString);
        return cachedParse(userAgent);
    }

    public UserAgent parse(UserAgent userAgent) {
        userAgent.reset();
        return cachedParse(userAgent);
    }


    private synchronized UserAgent cachedParse(UserAgent userAgent) {

        String userAgentString = userAgent.getUserAgentString();
        UserAgent cachedValue = userAgentResource.getfromCache(userAgentString);
        if (cachedValue != null) {
            userAgent.clone(cachedValue);
        } else {
            cachedValue = new UserAgent(nonCachedParse(userAgent));
            userAgentResource.putUserAgentToCache(userAgentString, cachedValue);
        }
        // We have our answer.
        return userAgent;
    }

    private UserAgent nonCachedParse(UserAgent userAgent) {

        boolean setVerboseTemporarily = userAgent.isDebug();

        // Reset all Matchers
        for (Matcher matcher : allMatchers) {
            matcher.reset(setVerboseTemporarily);
        }

        userAgent = flattener.parse(userAgent);

        // Fire all Analyzers
        for (Matcher matcher : allMatchers) {
            matcher.analyze(userAgent);
        }

        userAgent.processSetAll();
        return hardCodedPostProcessing(userAgent);
    }

    private UserAgent hardCodedPostProcessing(UserAgent userAgent){
        // If it is really really bad ... then it is a Hacker.
        if ("true".equals(userAgent.getValue(SYNTAX_ERROR))) {
            if (userAgent.get(DEVICE_CLASS).getConfidence() == -1 &&
                userAgent.get(OPERATING_SYSTEM_CLASS).getConfidence() == -1 &&
                userAgent.get(LAYOUT_ENGINE_CLASS).getConfidence() == -1)  {

                userAgent.set(DEVICE_CLASS, "Hacker", 10);
                userAgent.set(DEVICE_BRAND, "Hacker", 10);
                userAgent.set(DEVICE_NAME, "Hacker", 10);
                userAgent.set(DEVICE_VERSION, "Hacker", 10);
                userAgent.set(OPERATING_SYSTEM_CLASS, "Hacker", 10);
                userAgent.set(OPERATING_SYSTEM_NAME, "Hacker", 10);
                userAgent.set(OPERATING_SYSTEM_VERSION, "Hacker", 10);
                userAgent.set(LAYOUT_ENGINE_CLASS, "Hacker", 10);
                userAgent.set(LAYOUT_ENGINE_NAME, "Hacker", 10);
                userAgent.set(LAYOUT_ENGINE_VERSION, "Hacker", 10);
                userAgent.set(LAYOUT_ENGINE_VERSION_MAJOR, "Hacker", 10);
                userAgent.set(AGENT_CLASS, "Hacker", 10);
                userAgent.set(AGENT_NAME, "Hacker", 10);
                userAgent.set(AGENT_VERSION, "Hacker", 10);
                userAgent.set(AGENT_VERSION_MAJOR, "Hacker", 10);
                userAgent.set("HackerToolkit", "Unknown", 10);
                userAgent.set("HackerAttackVector", "Unknown", 10);
            }
        }

        // !!!!!!!!!! NOTE !!!!!!!!!!!!
        // IF YOU ADD ANY EXTRA FIELDS YOU MUST ADD THEM TO THE BUILDER TOO !!!!
        // TODO: Perhaps this should be more generic. Like a "Post processor"  (Generic: Create fields from fields)?
        addMajorVersionField(userAgent, AGENT_VERSION, AGENT_VERSION_MAJOR);
        addMajorVersionField(userAgent, LAYOUT_ENGINE_VERSION, LAYOUT_ENGINE_VERSION_MAJOR);
        addMajorVersionField(userAgent, "WebviewAppVersion", "WebviewAppVersionMajor");

        concatFieldValuesNONDuplicated(userAgent, "AgentNameVersion",               AGENT_NAME,             AGENT_VERSION);
        concatFieldValuesNONDuplicated(userAgent, "AgentNameVersionMajor",          AGENT_NAME,             AGENT_VERSION_MAJOR);
        concatFieldValuesNONDuplicated(userAgent, "WebviewAppNameVersionMajor",     "WebviewAppName",       "WebviewAppVersionMajor");
        concatFieldValuesNONDuplicated(userAgent, "LayoutEngineNameVersion",        LAYOUT_ENGINE_NAME,     LAYOUT_ENGINE_VERSION);
        concatFieldValuesNONDuplicated(userAgent, "LayoutEngineNameVersionMajor",   LAYOUT_ENGINE_NAME,     LAYOUT_ENGINE_VERSION_MAJOR);
        concatFieldValuesNONDuplicated(userAgent, "OperatingSystemNameVersion",     OPERATING_SYSTEM_NAME,  OPERATING_SYSTEM_VERSION);

        // The device brand field is a mess.
        UserAgent.AgentField deviceBrand = userAgent.get(DEVICE_BRAND);
        if (deviceBrand.getConfidence() >= 0) {
            userAgent.set(
                DEVICE_BRAND,
                Normalize.brand(deviceBrand.getValue()),
                deviceBrand.getConfidence() + 1);
        }

        // The email address is a mess
        UserAgent.AgentField email = userAgent.get("AgentInformationEmail");
        if (email != null && email.getConfidence() >= 0) {
            userAgent.set(
                "AgentInformationEmail",
                Normalize.email(email.getValue()),
                email.getConfidence() + 1);
        }

        // Make sure the DeviceName always starts with the DeviceBrand
        UserAgent.AgentField deviceName = userAgent.get(DEVICE_NAME);
        if (deviceName.getConfidence() >= 0) {
            deviceBrand = userAgent.get(DEVICE_BRAND);
            String deviceNameValue = deviceName.getValue();
            String deviceBrandValue = deviceBrand.getValue();
            if (deviceName.getConfidence() >= 0 &&
                deviceBrand.getConfidence() >= 0 &&
                !deviceBrandValue.equals("Unknown")) {
                // In some cases it does start with the brand but without a separator following the brand
                deviceNameValue = Normalize.cleanupDeviceBrandName(deviceBrandValue, deviceNameValue);
            } else {
                deviceNameValue = Normalize.brand(deviceNameValue);
            }

            userAgent.set(
                DEVICE_NAME,
                deviceNameValue,
                deviceName.getConfidence() + 1);
        }
        return userAgent;
    }

    private void concatFieldValuesNONDuplicated(UserAgent userAgent, String targetName, String firstName, String secondName) {
        UserAgent.AgentField firstField = userAgent.get(firstName);
        UserAgent.AgentField secondField = userAgent.get(secondName);

        String first = null;
        long firstConfidence = -1;
        String second = null;
        long secondConfidence = -1;

        if (firstField != null) {
            first = firstField.getValue();
            firstConfidence = firstField.getConfidence();
        }
        if (secondField != null) {
            second = secondField.getValue();
            secondConfidence = secondField.getConfidence();
        }

        if (first == null && second == null) {
            return; // Nothing to do
        }

        if (second == null) {
            if (firstConfidence >= 0){
                userAgent.set(targetName, first, firstConfidence);
                return;
            }
            return; // Nothing to do
        } else {
            if (first == null) {
                if (secondConfidence >= 0) {
                    userAgent.set(targetName, second, secondConfidence);
                    return;
                }
                return;
            }
        }

        if (first.equals(second)) {
            userAgent.set(targetName, first, firstConfidence);
        } else {
            if (second.startsWith(first)) {
                userAgent.set(targetName, second, secondConfidence);
            } else {
                userAgent.set(targetName, first + " " + second, Math.max(firstField.getConfidence(), secondField.getConfidence()));
            }
        }
    }

    private void addMajorVersionField(UserAgent userAgent, String versionName, String majorVersionName) {
        UserAgent.AgentField agentVersionMajor = userAgent.get(majorVersionName);
        if (agentVersionMajor == null || agentVersionMajor.getConfidence() == -1) {
            UserAgent.AgentField agentVersion = userAgent.get(versionName);
            if (agentVersion != null) {
                userAgent.set(
                    majorVersionName,
                    VersionSplitter.getSingleVersion(agentVersion.getValue(), 1),
                    agentVersion.getConfidence());
            }
        }
    }

    public void inform(String key, String value, ParseTree ctx) {
        inform(key, key, value, ctx);
        inform(key + "=\"" + value + '"', key, value, ctx);
    }

    private void inform(String match, String key, String value, ParseTree ctx) {
        Set<MatcherAction> relevantActions = informMatcherActions.get(match.toLowerCase());
        if (verbose) {
            if (relevantActions == null) {
                LOG.info("--- Have (0): {}", match);
            } else {
                LOG.info("+++ Have ({}): {}", relevantActions.size(), match);

                int count = 1;
                for (MatcherAction action: relevantActions) {
                    LOG.info("+++ -------> ({}): {}", count, action.toString());
                    count++;
                }
            }
        }

        if (relevantActions != null) {
            for (MatcherAction matcherAction : relevantActions) {
                matcherAction.inform(key, value, ctx);
            }
        }
    }

    // ===============================================================================================================

    public static class GetAllPathsAnalyzer extends Analyzer {
        final List<String> values = new ArrayList<>(128);
        final UserAgentTreeFlattener flattener;

        private final UserAgent result;

        GetAllPathsAnalyzer(String useragent) {
            flattener = new UserAgentTreeFlattener(this);
            result = flattener.parse(useragent);
        }

        public List<String> getValues() {
            return values;
        }

        public UserAgent getResult() {
            return result;
        }

        public void inform(String path, String value, ParseTree ctx) {
            values.add(path);
            values.add(path + "=\"" + value + "\"");
        }

        public void informMeAbout(MatcherAction matcherAction, String keyPattern) {
        }
    }

    @SuppressWarnings({"unused"})
    public static List<String> getAllPaths(String agent) {
        return new GetAllPathsAnalyzer(agent).getValues();
    }

    public static GetAllPathsAnalyzer getAllPathsAnalyzer(String agent) {
        return new GetAllPathsAnalyzer(agent);
    }


}
