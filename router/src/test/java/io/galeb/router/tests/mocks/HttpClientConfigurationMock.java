/*
 * Copyright (c) 2014-2017 Globo.com - ATeam
 * All rights reserved.
 *
 * This source is subject to the Apache License, Version 2.0.
 * Please see the LICENSE file for more information.
 *
 * Authors: See AUTHORS file
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.galeb.router.tests.mocks;

import com.google.gson.Gson;
import io.galeb.core.entity.BalancePolicy;
import io.galeb.core.entity.Environment;
import io.galeb.core.entity.HealthStatus;
import io.galeb.core.entity.Pool;
import io.galeb.core.entity.Project;
import io.galeb.core.entity.Rule;
import io.galeb.core.entity.RuleOrdered;
import io.galeb.core.entity.Target;
import io.galeb.core.entity.VirtualHost;
import io.galeb.core.entity.VirtualhostGroup;
import io.galeb.core.enums.SystemEnv;
import io.galeb.router.client.hostselectors.HostSelectorLookup;
import io.galeb.router.sync.HttpClient;
import io.galeb.router.sync.ManagerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.*;

@Configuration
@Profile({ "test" })
public class HttpClientConfigurationMock {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Bean
    HttpClient httpClientService() {
        logger.warn("Using " + this);

        return new HttpClient() {

            @Override
            public void getResponseBody(String url, String etag, OnCompletedCallBack callBack) {
                if (url != null && url.startsWith(SystemEnv.MANAGER_URL.getValue() + "/virtualhostscached/")) {
                    Environment environment = new Environment();
                    environment.setName("desenv");
                    String newEtag = UUID.randomUUID().toString();
                    Project project = new Project();
                    project.setName("projectX");
                    VirtualHost virtuahost = new VirtualHost();
                    virtuahost.setName("test.com");
                    virtuahost.setEnvironments(Collections.singleton(environment));
                    virtuahost.setProject(project);
                    // FIX: IPACL_ALLOW, "127.0.0.0/8,0:0:0:0:0:0:0:1/128,10.*.*.*,172.*.*.*" REMOVED
                    virtuahost.setVersion(0L);
                    Pool pool = new Pool();
                    pool.setName("pool_test");
                    Target target = new Target();
                    target.setName("http://127.0.0.1:8080");
                    HealthStatus healthStatusHealthy = new HealthStatus();
                    healthStatusHealthy.setStatus(HealthStatus.Status.HEALTHY);
                    healthStatusHealthy.setStatusDetailed("OK");
                    target.setHealthStatus(Collections.singleton(healthStatusHealthy));
                    pool.setTargets(Collections.singleton(target));
                    BalancePolicy balancePolicyRR = new BalancePolicy();
                    balancePolicyRR.setName(HostSelectorLookup.ROUNDROBIN.toString());
                    pool.setBalancepolicy(balancePolicyRR);
                    Rule rule_slash = new Rule();
                    rule_slash.setName("rule_test_slash");
                    rule_slash.setPools(Collections.singleton(pool));
                    rule_slash.setMatching("/");
                    RuleOrdered ruleOrderedSlash = new RuleOrdered();
                    ruleOrderedSlash.setOrder(Integer.MAX_VALUE - 1);
                    ruleOrderedSlash.setEnvironment(environment);
                    ruleOrderedSlash.setRule(rule_slash);

                    Rule other_rule = new Rule();
                    other_rule.setName("other_rule");
                    other_rule.setPools(Collections.singleton(pool));
                    other_rule.setMatching("/search");
                    RuleOrdered ruleOrderedSearch = new RuleOrdered();
                    ruleOrderedSearch.setOrder(0);
                    ruleOrderedSearch.setRule(other_rule);
                    final VirtualhostGroup virtualhostGroup = new VirtualhostGroup();

                    Set<RuleOrdered> rulesOrdered = new HashSet<>();
                    rulesOrdered.add(ruleOrderedSlash);
                    rulesOrdered.add(ruleOrderedSearch);
                    virtualhostGroup.setRulesordered(rulesOrdered);
                    virtuahost.setVirtualhostgroup(virtualhostGroup);
                    ManagerClient.Virtualhosts virtualhostsFromManager = new ManagerClient.Virtualhosts();
                    virtualhostsFromManager.virtualhosts = Collections.singleton(virtuahost).toArray(new VirtualHost[0]);
                    //FIX: Unexpected error occurred in scheduled task. java.lang.ArrayStoreException
                    callBack.onCompleted(new Gson().toJson(virtualhostsFromManager));
                }
            }

            @Override
            public void post(String url, String etag) {
                logger.info("sending POST to Manager (ignored) with etag " + etag);
            }
        };
    }
}
