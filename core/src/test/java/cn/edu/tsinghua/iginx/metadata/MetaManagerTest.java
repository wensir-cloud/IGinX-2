package cn.edu.tsinghua.iginx.metadata;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Created on 04/03/2021.
 * Description:
 *
 * @author iznauy
 */
public class MetaManagerTest {

    public MetaManager metaManager;

    @Before
    public void init() {
        metaManager = MetaManager.getInstance();
    }

    @Test
    public void testGetDatabaseList() {
        List<DatabaseMeta> databaseMetaList = metaManager.getDatabaseList();
        for (DatabaseMeta databaseMeta: databaseMetaList) {
            System.out.println(databaseMeta);
        }
        assertEquals(2, databaseMetaList.size());
    }

    @Test
    public void testGetIginxList() {
        List<IginxMeta> iginxMetaList = metaManager.getIginxList();
        for (IginxMeta iginxMeta: iginxMetaList) {
            System.out.println(iginxMeta);
        }
        assertEquals(1, iginxMetaList.size());
        IginxMeta iginxMeta = iginxMetaList.get(0);
        assertEquals("127.0.0.1", iginxMeta.getIp());
        assertEquals(6324, iginxMeta.getPort());
    }

    @Test
    public void testCreateFragment() {
        List<Long> databaseIds = metaManager.chooseDatabaseIdsForNewFragment();
        FragmentMeta fragmentMeta = new FragmentMeta("root.ln", 0, 0, databaseIds);
        metaManager.createFragment(fragmentMeta);
    }

}
