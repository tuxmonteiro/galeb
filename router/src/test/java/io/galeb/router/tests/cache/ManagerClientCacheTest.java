package io.galeb.router.tests.cache;

import io.galeb.core.entity.Environment;
import io.galeb.core.entity.Project;
import io.galeb.core.entity.VirtualHost;
import io.galeb.router.configurations.ManagerClientCacheConfiguration.ManagerClientCache;
import io.galeb.router.sync.ManagerClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

public class ManagerClientCacheTest {

    private final ManagerClient.Virtualhosts virtualhosts = new ManagerClient.Virtualhosts();
    private final ManagerClientCache managerClientCache = new ManagerClientCache();
    private final Environment env1 = new Environment();
    private final Project project1 = new Project();
    private final String[] virtualhostNames = {"test1", "test2", "test3", "test4"};
    private final String initialEtag = UUID.randomUUID().toString();

    @Before
    public void setUp() {
        env1.setName("env1");
        project1.setName("project1");
        virtualhosts.etag = initialEtag;
        virtualhosts.virtualhosts = Arrays.stream(virtualhostNames).map(name -> {
            final VirtualHost virtualHost = new VirtualHost();
            virtualHost.setName(name);
            virtualHost.setEnvironments(Collections.singleton(env1));
            virtualHost.setProject(project1);
            virtualHost.setVersion(0L);
            return virtualHost;
        }).toArray(VirtualHost[]::new);
    }

    @After
    public void cleanUp() {
        virtualhosts.virtualhosts = new VirtualHost[0];
        managerClientCache.clear();
    }

    @Test
    public void checkInitialHash() {
        Stream.of(virtualhosts.virtualhosts).forEach(virtualhost -> managerClientCache.put(virtualhost.getName(), virtualhost));
        assertThat(managerClientCache.etag(), equalTo(initialEtag));
    }

    @Test
    public void checkNewHash() {
        Stream.of(virtualhosts.virtualhosts).forEach(virtualhost -> managerClientCache.put(virtualhost.getName(), virtualhost));
        virtualhosts.etag = UUID.randomUUID().toString();
        VirtualHost virtualhost = new VirtualHost();
        virtualhost.setName("otherVirtualhost");
        virtualhost.setEnvironments(Collections.singleton(env1));
        virtualhost.setProject(project1);
        managerClientCache.put("otherVirtualhost", virtualhost);
        assertThat(managerClientCache.etag(), equalTo(virtualhosts.etag));
    }

    @Test
    public void checkForceNewHash() {
        Stream.of(virtualhosts.virtualhosts).forEach(virtualhost -> managerClientCache.put(virtualhost.getName(), virtualhost));
        String newHash = UUID.randomUUID().toString();
        managerClientCache.updateEtag(newHash);
        assertThat(managerClientCache.etag(), equalTo(newHash));
    }

}
