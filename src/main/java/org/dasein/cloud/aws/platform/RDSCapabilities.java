/**
 * Copyright (C) 2009-2015 Dell, Inc.
 * See annotations for authorship information
 *
 * ====================================================================
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
 * ====================================================================
 */

package org.dasein.cloud.aws.platform;

import org.dasein.cloud.AbstractCapabilities;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.Requirement;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.platform.DatabaseProduct;
import org.dasein.cloud.platform.RelationalDatabaseCapabilities;
import org.dasein.cloud.util.NamingConstraints;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * Description
 * <p>Created by stas: 05/08/2014 14:43</p>
 *
 * @author Stas Maksimov
 * @version 2014.08 initial version
 * @since 2014.08
 */
public class RDSCapabilities extends AbstractCapabilities<AWSCloud> implements RelationalDatabaseCapabilities {

    public RDSCapabilities( @Nonnull AWSCloud provider ) {
        super(provider);
    }

    @Override
    public @Nonnull String getProviderTermForBackup( Locale locale ) {
        return "Backup";
    }

    @Override
    public @Nonnull String getProviderTermForDatabase( Locale locale ) {
        return "database";
    }

    @Override
    public @Nonnull String getProviderTermForSnapshot( Locale locale ) {
        return "snapshot";
    }

    @Nonnull
    @Override
    public Requirement requiresEngineVersion() throws CloudException, InternalException {
        return Requirement.NONE;
    }

    @Override
    public boolean supportsFirewallRules() throws CloudException, InternalException {
        return true;
    }

    @Override
    public boolean supportsHighAvailability() throws CloudException, InternalException {
        return true;
    }

    @Override
    public boolean supportsLowAvailability() throws CloudException, InternalException {
        return true;
    }

    @Override
    public boolean supportsMaintenanceWindows() throws CloudException, InternalException {
        return true;
    }

    @Override
    public boolean supportsRootPasswordChange() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean supportsAlterDatabase() throws CloudException, InternalException {
        return true;
    }

    @Override
    public boolean supportsSnapshots() throws CloudException, InternalException {
        return true;
    }

    @Override
    public boolean supportsDatabaseBackups() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean supportsScheduledDatabaseBackups() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean supportsDemandBackups() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean supportsRestoreBackup() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean supportsDeleteBackup() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean supportsBackupConfigurations() throws CloudException, InternalException {
        return false;
    }

    @Override
    public @Nonnull NamingConstraints getRelationalDatabaseNamingConstraints() {
        return NamingConstraints.getAlphaNumeric(1, 63).constrainedBy('-');
    }

    @Nonnull @Override public NamingConstraints getAdminUsernameNamingConstraints( DatabaseProduct product ) throws CloudException, InternalException {
        return null;
    }

    @Nonnull @Override public NamingConstraints getAdminPasswordNamingConstraints( DatabaseProduct product ) throws CloudException, InternalException {
        return null;
    }

}
