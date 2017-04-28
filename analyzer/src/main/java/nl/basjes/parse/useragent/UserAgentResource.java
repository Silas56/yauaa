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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import nl.basjes.parse.useragent.analyze.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static nl.basjes.parse.useragent.UserAgent.*;

public class UserAgentResource   extends Analyzer{

    private static final int INFORM_ACTIONS_HASHMAP_SIZE = 300000;
    private static final int DEFAULT_PARSE_CACHE_SIZE = 10000*50;

    private static final Logger LOG = LoggerFactory.getLogger(UserAgentResource.class);
    protected List<Matcher>                     allMatchers             = new ArrayList<>();
    private Map<String, Set<MatcherAction>>     informMatcherActions    = new HashMap<>(INFORM_ACTIONS_HASHMAP_SIZE);
    private final Map<String, List<Map<String, List<String>>>> matcherConfigs = new HashMap<>(64);

    private boolean doingOnlyASingleTest = false;

    // If we want ALL fields this is null. If we only want specific fields this is a list of names.
    protected Set<String> wantedFieldNames = null;

    protected final List<Map<String, Map<String, String>>> testCases    = new ArrayList<>(2048);
    private Map<String, Map<String, String>> lookups2                    = new HashMap<>(128);

    private Yaml yaml;

    private com.google.common.cache.LoadingCache<String, UserAgent> parseCache2;

    public UserAgentResource() {
        initialize(true);
    }

    public List<Matcher> getAllMatchers(){
        return allMatchers;
    }


    public Map<String, Set<MatcherAction>> getInformMatcherActions(){
        return informMatcherActions;
    }

    public Map<String, Map<String, String>> getLookups(){
        return this.lookups2;
    }

    protected void initialize(boolean showMatcherStats) {
        logVersion();
        loadResources("classpath*:UserAgents/**/*.yaml", showMatcherStats);
        parseCache2 = CacheBuilder.newBuilder()
                .maximumSize(DEFAULT_PARSE_CACHE_SIZE)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build(new UserAgentCacheLoader());
    }

    public UserAgent getfromCache(String ua){
        try{
            UserAgent userAgent = parseCache2.get(ua);
            if (userAgent != null){
                UserAgent newAgent = new UserAgent();
                newAgent.clone(userAgent);
                return newAgent;
            }
        }catch (Exception e){
        }
        return null;
    }

    public void putUserAgentToCache(String ua, UserAgent userAgent){
        try{
            parseCache2.put(ua, userAgent);
        }catch (Exception e){
        }
    }

    public UserAgentResource(String resourceString) {
        loadResources(resourceString, true);
    }

    public static void logVersion(){
        String[] lines = {
            "For more information: https://github.com/nielsbasjes/yauaa",
            "Copyright (C) 2013-2017 Niels Basjes - License Apache 2.0"
        };
        String version = getVersion();
        int width = version.length();
        for (String line: lines) {
            width = Math.max(width, line.length());
        }

        LOG.info("");
        LOG.info("/-{}-\\", padding('-', width));
        logLine(version, width);
        LOG.info("+-{}-+", padding('-', width));
        for (String line: lines) {
            logLine(line, width);
        }
        LOG.info("\\-{}-/", padding('-', width));
        LOG.info("");
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

    public void loadResources(String resourceString, boolean showMatcherStats) {
        LOG.info("Loading from: \"{}\"", resourceString);

        yaml = new Yaml();

        Map<String, Resource> resources = new TreeMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resourceArray = resolver.getResources(resourceString);
            for (Resource resource:resourceArray) {
                resources.put(resource.getFilename(), resource);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        doingOnlyASingleTest = false;
        int maxFilenameLength = 0;

        if (resources.isEmpty()) {
            throw new InvalidParserConfigurationException("Unable to find ANY config files");
        }

        for (Map.Entry<String, Resource> resourceEntry : resources.entrySet()) {
            try {
                Resource resource = resourceEntry.getValue();
                String filename = resource.getFilename();
                maxFilenameLength = Math.max(maxFilenameLength, filename.length());
                loadResource(resource.getInputStream(), filename);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        LOG.info("Loaded {} files", resources.size());

        if (lookups2 != null && !lookups2.isEmpty()) {
            // All compares are done in a case insensitive way. So we lowercase ALL keys of the lookups beforehand.
            Map<String, Map<String, String>> cleanedLookups = new HashMap<>(lookups2.size());
            for (Map.Entry<String, Map<String, String>> lookupsEntry : lookups2.entrySet()) {
                Map<String, String> cleanedLookup = new HashMap<>(lookupsEntry.getValue().size());
                for (Map.Entry<String, String> entry : lookupsEntry.getValue().entrySet()) {
                    cleanedLookup.put(entry.getKey().toLowerCase(), entry.getValue());
                }
                cleanedLookups.put(lookupsEntry.getKey(), cleanedLookup);
            }
            lookups2 = cleanedLookups;
        }

        LOG.info("Building all matchers");
        int totalNumberOfMatchers = 0;
        int skippedMatchers = 0;
        if (matcherConfigs != null) {
            long fullStart = System.nanoTime();
            for (Map.Entry<String, Resource> resourceEntry : resources.entrySet()) {
                Resource resource = resourceEntry.getValue();
                String configFilename= resource.getFilename();
                List<Map<String, List<String>>> matcherConfig = matcherConfigs.get(configFilename);
                if (matcherConfig== null) {
                    continue; // No matchers in this file (probably only lookups and/or tests)
                }

                long start = System.nanoTime();
                int startSize = informMatcherActions.size();
                for (Map<String, List<String>> map : matcherConfig) {
                    try {
                        allMatchers.add(new Matcher(this, lookups2, wantedFieldNames, map));
                        totalNumberOfMatchers++;
                    } catch (UselessMatcherException ume) {
                        skippedMatchers++;
                    }
                }
                long stop = System.nanoTime();
                int stopSize = informMatcherActions.size();

                if (showMatcherStats) {
                    Formatter msg = new Formatter(Locale.ENGLISH);
                    msg.format("Building %4d matchers from %-" + maxFilenameLength + "s took %5d msec resulted in %8d extra hashmap entries",
                        matcherConfig.size(),
                        configFilename,
                        (stop - start) / 1000000,
                        stopSize - startSize);
                    LOG.info(msg.toString());
                }
            }
            long fullStop = System.nanoTime();

            Formatter msg = new Formatter(Locale.ENGLISH);
            msg.format("Building %4d (dropped %4d) matchers from %4d files took %5d msec resulted in %8d hashmap entries",
                totalNumberOfMatchers,
                skippedMatchers,
                matcherConfigs.size(),
                (fullStop-fullStart)/1000000,
                informMatcherActions.size());
            LOG.info(msg.toString());

        }
        LOG.info("Analyzer stats");
        LOG.info("Lookups      : {}", (lookups2 == null) ? 0 : lookups2.size());
        LOG.info("Matchers     : {} (total:{} ; dropped: {})", allMatchers.size(), totalNumberOfMatchers, skippedMatchers);
        LOG.info("Hashmap size : {}", informMatcherActions.size());
        LOG.info("Testcases    : {}", testCases .size());
//        LOG.info("All possible field names:");
//        int count = 1;
//        for (String fieldName : getAllPossibleFieldNames()) {
//            LOG.info("- {}: {}", count++, fieldName);
//        }
    }

    /**
     * Used by some unit tests to get rid of all the standard tests and focus on the experiment at hand.
     */
    public void eraseTestCases() {
        testCases.clear();
    }

    public Set<String> getAllPossibleFieldNames() {
        Set<String> results = new TreeSet<>();
        results.addAll(HARD_CODED_GENERATED_FIELDS);
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

    private void loadResource(InputStream yamlStream, String filename) {
        Object loadedYaml;
        try {
            loadedYaml = yaml.load(yamlStream);
        } catch (Exception e) {
            LOG.error("Caught exception during parse of file {}", filename);
            throw e;
        }

        if (!(loadedYaml instanceof Map)) {
            throw new InvalidParserConfigurationException(
                "Yaml config  ("+filename+"): File must be a Map");
        }

        @SuppressWarnings({"unchecked"}) // Ignoring the possibly wrong generic here
        Object rawConfig = ((Map<String, Object>) loadedYaml).get("config");
        if (rawConfig == null) {
            throw new InvalidParserConfigurationException(
                "Yaml config ("+filename+"): Missing 'config' top level entry");
        }
        if (!(rawConfig instanceof List)) {
            throw new InvalidParserConfigurationException(
                "Yaml config ("+filename+"): Top level 'config' must be a Map");
        }

        @SuppressWarnings({"unchecked"}) // Ignoring the possibly wrong generic here
        List<Object> configList = (List<Object>)rawConfig;
        int entryCount = 0;
        for (Object configEntry: configList) {
            entryCount++;
            if (!(configEntry instanceof Map)) {
                throw new InvalidParserConfigurationException(
                    "Yaml config ("+filename+" ["+entryCount+"]): Entry must be a Map");
            }
            @SuppressWarnings({"unchecked"}) // Ignoring the possibly wrong generic here
            Map<String, Object> entry = (Map<String, Object>) configEntry;
            if (entry.size() != 1) {
                StringBuilder sb = new StringBuilder();
                for (String key: entry.keySet()) {
                    sb.append('"').append(key).append("\" ");
                }
                throw new InvalidParserConfigurationException(
                    "Yaml config ("+filename+" ["+entryCount+"]): Entry has more than one child: "+sb.toString());
            }

            Map.Entry<String, Object> onlyEntry = entry.entrySet().iterator().next();
            String key   = onlyEntry.getKey();
            Object value = onlyEntry.getValue();
            switch (key) {

                case "lookup":
                    if (!(value instanceof Map)) {
                        throw new InvalidParserConfigurationException(
                            "Yaml config ("+filename+" ["+entryCount+"]): Entry 'lookup' must be a Map");
                    }

                    @SuppressWarnings({"unchecked"}) // Ignoring the possibly wrong generic here
                    Map<String, Object> newLookup = (Map<String, Object>)value;
                    Object rawName = newLookup.get("name");
                    if (rawName == null) {
                        throw new InvalidParserConfigurationException(
                            "Yaml config ("+filename+" ["+entryCount+"]): Lookup does not have 'name'");
                    }
                    if (!(rawName instanceof String)) {
                        throw new InvalidParserConfigurationException(
                            "Yaml config ("+filename+" ["+entryCount+"]): Lookup 'name' must be a String");
                    }

                    Object rawMap = newLookup.get("map");
                    if (rawMap == null) {
                        throw new InvalidParserConfigurationException(
                            "Yaml config ("+filename+" ["+entryCount+"]): Lookup does not have 'map'");
                    }
                    if (!(rawMap instanceof Map)) {
                        throw new InvalidParserConfigurationException(
                            "Yaml config ("+filename+" ["+entryCount+"]): Lookup 'map' must be a Map");
                    }

                    @SuppressWarnings({"unchecked"}) // Ignoring the possibly wrong generic here
                    Map<String, String> map = (Map<String, String>)rawMap;
                    lookups2.put((String)rawName, map);
                    break;

                case "matcher":
                    if (!(value instanceof Map)) {
                        throw new InvalidParserConfigurationException(
                            "Yaml config ("+filename+"): Entry 'matcher' must be a Map");
                    }
                    @SuppressWarnings({"unchecked"}) // Ignoring the possibly wrong generic here
                    Map<String, List<String>> matcherConfig = (Map<String, List<String>>) value;

                    List<Map<String, List<String>>> matcherConfigList = matcherConfigs.get(filename);
                    if (matcherConfigList == null) {
                        matcherConfigList = new ArrayList<>(32);
                        matcherConfigs.put(filename, matcherConfigList);
                    }
                    matcherConfigList.add(matcherConfig);
                    break;

                case "test":
                    if (!doingOnlyASingleTest) {
                        if (!(value instanceof Map)) {
                            throw new InvalidParserConfigurationException(
                                "Yaml config (" + filename + "): Entry 'testcase' must be a Map");
                        }
                        @SuppressWarnings({"unchecked"}) // Ignoring the possibly wrong generic here
                                Map<String, Map<String, String>> testCase = (Map<String, Map<String, String>>) value;
                        Map<String, String> metaData = testCase.get("metaData");
                        if (metaData == null) {
                            metaData = new HashMap<>();
                            testCase.put("metaData", metaData);
                        }
                        metaData.put("filename", filename);
                        metaData.put("fileentry", String.valueOf(entryCount));

                        @SuppressWarnings("unchecked")
                        List<String> options = (List<String>) testCase.get("options");
                        Map<String, String> expected = testCase.get("expected");
                        if (options != null) {
                            if (options.contains("only")) {
                                doingOnlyASingleTest = true;
                                testCases.clear();
                            }
                        }
                        if (expected == null || expected.isEmpty()) {
                            doingOnlyASingleTest = true;
                            testCases.clear();
                        }

                        testCases.add(testCase);
                    }
                    break;

                default:
                    throw new InvalidParserConfigurationException(
                        "Yaml config ("+filename+"): Found unexpected config entry: " + key + ", allowed are 'lookup, 'matcher' and 'test'");
            }
        }

    }


    private final static boolean verbose = false;


    public void informMeAbout(MatcherAction matcherAction, String keyPattern) {
        String hashKey = keyPattern.toLowerCase();
        Set<MatcherAction> analyzerSet = informMatcherActions.get(hashKey);
        if (analyzerSet == null) {
            analyzerSet = new HashSet<>();
            informMatcherActions.put(hashKey, analyzerSet);
        }
        analyzerSet.add(matcherAction);
    }

    public void inform(String key, String value, ParseTree ctx) {
        inform(key, key, value, ctx);
        inform(key + "=\"" + value + '"', key, value, ctx);
    }

    private void inform(String match, String key, String value, ParseTree ctx) {
        Set<MatcherAction> relevantActions = informMatcherActions.get(match.toLowerCase());
//        if (verbose) {
//            if (relevantActions == null) {
//                LOG.info("--- Have (0): {}", match);
//            } else {
//                LOG.info("+++ Have ({}): {}", relevantActions.size(), match);
//
//                int count = 1;
//                for (MatcherAction action: relevantActions) {
//                    LOG.info("+++ -------> ({}): {}", count, action.toString());
//                    count++;
//                }
//            }
//        }

        if (relevantActions != null) {
            for (MatcherAction matcherAction : relevantActions) {
                matcherAction.inform(key, value, ctx);
            }
        }
    }


    public static final List<String> HARD_CODED_GENERATED_FIELDS = new ArrayList<>();
    static {
        HARD_CODED_GENERATED_FIELDS.add(SYNTAX_ERROR);
        HARD_CODED_GENERATED_FIELDS.add(AGENT_VERSION_MAJOR);
        HARD_CODED_GENERATED_FIELDS.add(LAYOUT_ENGINE_VERSION_MAJOR);
        HARD_CODED_GENERATED_FIELDS.add("AgentNameVersion");
        HARD_CODED_GENERATED_FIELDS.add("AgentNameVersionMajor");
        HARD_CODED_GENERATED_FIELDS.add("LayoutEngineNameVersion");
        HARD_CODED_GENERATED_FIELDS.add("LayoutEngineNameVersionMajor");
        HARD_CODED_GENERATED_FIELDS.add("OperatingSystemNameVersion");
        HARD_CODED_GENERATED_FIELDS.add("WebviewAppVersionMajor");
        HARD_CODED_GENERATED_FIELDS.add("WebviewAppNameVersionMajor");
    }


    private static class UserAgentCacheLoader extends CacheLoader<String, UserAgent> {
        public UserAgentCacheLoader() {
        }
        @Override
        public UserAgent load(String key) throws Exception {
            return null;
        }
    }

}
