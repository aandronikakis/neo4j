/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.causalclustering;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.io.File;

import org.neo4j.causalclustering.core.CoreGraphDatabase;
import org.neo4j.causalclustering.discovery.Cluster;
import org.neo4j.causalclustering.discovery.CoreClusterMember;
import org.neo4j.causalclustering.discovery.IpFamily;
import org.neo4j.causalclustering.discovery.SharedDiscoveryService;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.enterprise.configuration.OnlineBackupSettings;
import org.neo4j.kernel.impl.store.format.standard.Standard;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.restore.RestoreDatabaseCommand;
import org.neo4j.test.DbRepresentation;
import org.neo4j.test.rule.SuppressOutput;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.fs.DefaultFileSystemRule;

import static java.util.Collections.emptyMap;
import static org.junit.Assert.assertEquals;
import static org.neo4j.causalclustering.BackupCoreIT.backupAddress;
import static org.neo4j.causalclustering.discovery.Cluster.dataMatchesEventually;
import static org.neo4j.causalclustering.helpers.DataCreator.createEmptyNodes;
import static org.neo4j.util.TestHelpers.runBackupToolFromOtherJvmToGetExitCode;

public class ClusterSeedingIT
{
    private Cluster backupCluster;
    private Cluster cluster;
    private FileSystemAbstraction fsa;

    public TestDirectory testDir = TestDirectory.testDirectory();
    public DefaultFileSystemRule fileSystemRule = new DefaultFileSystemRule();
    public SuppressOutput suppressOutput = SuppressOutput.suppressAll();
    @Rule
    public RuleChain rules = RuleChain.outerRule( fileSystemRule ).around( testDir ).around( suppressOutput );

    private File baseBackupDir;

    @Before
    public void setup() throws Exception
    {
        fsa = fileSystemRule.get();
        backupCluster = new Cluster( testDir.directory( "cluster-for-backup" ), 3, 0,
                new SharedDiscoveryService(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Standard
                .LATEST_NAME, IpFamily.IPV4, false );

        cluster = new Cluster( testDir.directory( "cluster-b" ), 3, 0,
                new SharedDiscoveryService(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Standard.LATEST_NAME,
                IpFamily.IPV4, false );

        baseBackupDir = testDir.directory( "backups" );
    }

    @After
    public void after() throws Exception
    {
        if ( backupCluster != null )
        {
            backupCluster.shutdown();
        }
        if ( cluster != null )
        {
            cluster.shutdown();
        }
    }

    private File createBackupUsingAnotherCluster() throws Exception
    {
        backupCluster.start();
        CoreGraphDatabase db = BackupCoreIT.createSomeData( backupCluster );

        File backup = createBackup( backupCluster, "some-backup" );
        backupCluster.shutdown();

        return backup;
    }

    private File createBackup( Cluster cluster, String backupName ) throws Exception
    {
        String[] args = BackupCoreIT.backupArguments( backupAddress( cluster ), baseBackupDir, backupName );
        assertEquals( 0, runBackupToolFromOtherJvmToGetExitCode( testDir.absolutePath(), args ) );
        return new File( baseBackupDir, backupName );
    }

    @Test
    public void shouldRestoreBySeedingAllMembers() throws Throwable
    {
        // given
        File backupDir = createBackupUsingAnotherCluster();
        Config config = Config.defaults( OnlineBackupSettings.online_backup_enabled, Boolean.FALSE.toString() );
        DbRepresentation before = DbRepresentation.of( backupDir, config );

        // when
        for ( CoreClusterMember coreClusterMember : cluster.coreMembers() )
        {
            String databaseName = coreClusterMember
                    .getMemberConfig().get( GraphDatabaseSettings.active_database );
            new RestoreDatabaseCommand( fsa, backupDir, coreClusterMember.getMemberConfig(), databaseName, true )
                    .execute();
        }
        cluster.start();

        // then
        dataMatchesEventually( before, cluster.coreMembers() );
    }

    @Test
    public void shouldSeedNewMemberFromEmptyIdleCluster() throws Throwable
    {
        // given
        Monitors monitors = new Monitors();
        cluster = new Cluster( testDir.directory( "cluster-b" ), 3, 0,
                new SharedDiscoveryService(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Standard.LATEST_NAME,
                IpFamily.IPV4, false );
        cluster.start();

        // when: creating a backup
        File backupDir = createBackup( cluster, "the-backup" );

        // and: seeding new member with said backup
        CoreClusterMember newMember = cluster.addCoreMemberWithId( 3 );
        String databaseName = newMember.getMemberConfig().get( GraphDatabaseSettings.active_database );
        new RestoreDatabaseCommand( fsa, backupDir, newMember.getMemberConfig(), databaseName, true ).execute();
        newMember.start();

        // then
        dataMatchesEventually( DbRepresentation.of( newMember.database() ), cluster.coreMembers() );
    }

    @Test
    public void shouldSeedNewMemberFromNonEmptyIdleCluster() throws Throwable
    {
        // given
        Monitors monitors = new Monitors();
        cluster = new Cluster( testDir.directory( "cluster-b" ), 3, 0,
                new SharedDiscoveryService(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Standard.LATEST_NAME,
                IpFamily.IPV4, false );

        cluster.start();
        createEmptyNodes( cluster, 100 );

        // when: creating a backup
        File backupDir = createBackup( cluster, "the-backup" );

        // and: seeding new member with said backup
        CoreClusterMember newMember = cluster.addCoreMemberWithId( 3 );
        String databaseName = newMember.getMemberConfig().get( GraphDatabaseSettings.active_database );
        new RestoreDatabaseCommand( fsa, backupDir, newMember.getMemberConfig(), databaseName, true ).execute();
        newMember.start();

        // then
        dataMatchesEventually( DbRepresentation.of( newMember.database() ), cluster.coreMembers() );
    }

    @Test
    @Ignore( "need to seed all members for now" )
    public void shouldRestoreBySeedingSingleMember() throws Throwable
    {
        // given
        File backupDir = createBackupUsingAnotherCluster();
        DbRepresentation before = DbRepresentation.of( backupDir );

        // when
        fsa.copyRecursively( backupDir, cluster.getCoreMemberById( 0 ).storeDir() );
        cluster.getCoreMemberById( 0 ).start();
        Thread.sleep( 2_000 );
        cluster.getCoreMemberById( 1 ).start();
        cluster.getCoreMemberById( 2 ).start();

        // then
        dataMatchesEventually( before, cluster.coreMembers() );
    }
}
