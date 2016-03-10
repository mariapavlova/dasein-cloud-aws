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

package org.dasein.cloud.aws.identity;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import org.apache.log4j.Logger;
import org.dasein.cloud.*;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.aws.compute.EC2Exception;
import org.dasein.cloud.aws.compute.EC2Method;
import org.dasein.cloud.identity.*;
import org.dasein.cloud.util.APITrace;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class Keypairs extends AbstractShellKeySupport<AWSCloud> {
	static private final Logger logger = AWSCloud.getLogger(Keypairs.class);
	
    private volatile transient KeypairsCapabilities capabilities;

	public Keypairs(@Nonnull AWSCloud provider) {
		super(provider);
	}
	
	@Override
	public @Nonnull SSHKeypair createKeypair(@Nonnull String name) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Keypair.createKeypair");
        try {
            String regionId = getContext().getRegionId();
            if( regionId == null ) {
                throw new InternalException("No region was set for this request.");
            }
            Map<String,String> parameters = getProvider().getStandardParameters(EC2Method.CREATE_KEY_PAIR);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("KeyName", name);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            String material = null, fingerprint = null;
            blocks = doc.getElementsByTagName("CreateKeyPairResponse");
            for( int i=0; i<blocks.getLength(); i++ ) {
                Node item = blocks.item(i);
                NodeList attrs = item.getChildNodes();

                for( int j=0; j<attrs.getLength(); j++ ) {
                    Node attr = attrs.item(j);

                    if( attr.getNodeName().equalsIgnoreCase("keyMaterial")) {
                        material = attr.getFirstChild().getNodeValue();

                    }
                    else if( attr.getNodeName().equalsIgnoreCase("keyFingerPrint")) {
                        fingerprint = attr.getFirstChild().getNodeValue();

                    }
                }
            }
            if( fingerprint == null || material == null ) {
                throw new GeneralCloudException("Invalid response to attempt to create the keypair");
            }
            SSHKeypair key = new SSHKeypair();

            try {
                key.setPrivateKey(material.getBytes("utf-8"));
            }
            catch( UnsupportedEncodingException e ) {
                throw new InternalException(e);
            }
            key.setFingerprint(fingerprint);
            key.setName(name);
            key.setProviderKeypairId(name);
            key.setProviderOwnerId(getContext().getAccountNumber());
            key.setProviderRegionId(regionId);
            return key;
        }
        finally {
            APITrace.end();
        }
	}

	@Override
	public void deleteKeypair(@Nonnull String name) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Keypair.deleteKeypair");
        try {
            Map<String,String> parameters = getProvider().getStandardParameters(EC2Method.DELETE_KEY_PAIR);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("KeyName", name);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && code.startsWith("InvalidKeyPair") ) {
                    return;
                }
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("return");
            if( blocks.getLength() > 0 ) {
                if( !blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true") ) {
                    throw new GeneralCloudException("Deletion of keypair denied.");
                }
            }
        }
        finally {
            APITrace.end();
        }
	}

	@Override
	public @Nullable String getFingerprint(@Nonnull String name) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Keypair.getFingerprint");
        try {
            Map<String,String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_KEY_PAIRS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("KeyName.1", name);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && code.startsWith("InvalidKeyPair") ) {
                    return null;
                }
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("keyFingerprint");
            if( blocks.getLength() > 0 ) {
                return blocks.item(0).getFirstChild().getNodeValue().trim();
            }
            throw new GeneralCloudException("Unable to identify key fingerprint.");
        }
        finally {
            APITrace.end();
        }
	}

    @Override
    public @Nullable SSHKeypair getKeypair(@Nonnull String name) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Keypair.getKeypair");
        try {
            String regionId = getContext().getRegionId();

            if( regionId == null ) {
                throw new InternalException("No region was set for this request.");
            }
            Map<String,String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_KEY_PAIRS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("KeyName.1", name);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && code.startsWith("InvalidKeyPair") ) {
                    return null;
                }
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("item");
            for( int i=0; i<blocks.getLength(); i++ ) {
                Node item = blocks.item(i);
                NodeList attrs = item.getChildNodes();
                String fingerprint = null;
                String keyName = null;

                for( int j=0; j<attrs.getLength(); j++ ) {
                    Node attr = attrs.item(j);

                    if( attr.getNodeName().equalsIgnoreCase("keyFingerprint") && attr.hasChildNodes() ) {
                        fingerprint = attr.getFirstChild().getNodeValue().trim();
                    }
                    else if( attr.getNodeName().equalsIgnoreCase("keyName") && attr.hasChildNodes() ) {
                        keyName = attr.getFirstChild().getNodeValue().trim();
                    }
                }
                if( keyName != null && keyName.equals(name) && fingerprint != null ) {
                    SSHKeypair kp = new SSHKeypair();

                    kp.setFingerprint(fingerprint);
                    kp.setName(keyName);
                    kp.setPrivateKey(null);
                    kp.setPublicKey(null);
                    kp.setProviderKeypairId(keyName);
                    kp.setProviderOwnerId(getContext().getAccountNumber());
                    kp.setProviderRegionId(regionId);
                    return kp;
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull ShellKeyCapabilities getCapabilities() throws CloudException, InternalException {
        if( capabilities == null ) {
            capabilities = new KeypairsCapabilities(getProvider());
        }
        return capabilities;
    }

    @Override
    public @Nonnull SSHKeypair importKeypair(@Nonnull String name, @Nonnull String material) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Keypair.importKeypair");
        try {
            String regionId = getContext().getRegionId();

            if( regionId == null ) {
                throw new InternalException("No region was set for this request.");
            }
            Map<String,String> parameters = getProvider().getStandardParameters(EC2Method.IMPORT_KEY_PAIR);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("KeyName", name);
            parameters.put("PublicKeyMaterial", material);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            String fingerprint = null;

            blocks = doc.getElementsByTagName("ImportKeyPairResponse");
            for( int i=0; i<blocks.getLength(); i++ ) {
                Node item = blocks.item(i);
                NodeList attrs = item.getChildNodes();

                for( int j=0; j<attrs.getLength(); j++ ) {
                    Node attr = attrs.item(j);

                    if( attr.getNodeName().equalsIgnoreCase("keyFingerPrint")) {
                        fingerprint = attr.getFirstChild().getNodeValue();

                    }
                }
            }
            if( fingerprint == null ) {
                throw new GeneralCloudException("Invalid response to attempt to create the keypair");
            }
            SSHKeypair key = new SSHKeypair();

            try {
                key.setPrivateKey(material.getBytes("utf-8"));
            }
            catch( UnsupportedEncodingException e ) {
                throw new InternalException(e);
            }
            key.setFingerprint(fingerprint);
            key.setName(name);
            key.setProviderKeypairId(name);
            key.setProviderOwnerId(getContext().getAccountNumber());
            key.setProviderRegionId(regionId);
            return key;
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Keypair.isSubscribed");
        try {
            getProvider().testContext();
            return true;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
	public @Nonnull Collection<SSHKeypair> list() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Keypair.list");
        try {
            String regionId = getContext().getRegionId();
            if( regionId == null ) {
                throw new InternalException("No region was set for this request.");
            }
            Map<String,String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_KEY_PAIRS);
            ArrayList<SSHKeypair> keypairs = new ArrayList<>();
            EC2Method method;
            NodeList blocks;
            Document doc;

            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && code.startsWith("InvalidKeyPair") ) {
                    return Collections.emptyList();
                }
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("item");
            for( int i=0; i<blocks.getLength(); i++ ) {
                Node item = blocks.item(i);

                if( item.hasChildNodes() ) {
                    NodeList attrs = item.getChildNodes();
                    String fingerprint = null;

                    String keyName = null;
                    for( int j=0; j<attrs.getLength(); j++ ) {
                        Node attr = attrs.item(j);

                        if( attr.getNodeName().equalsIgnoreCase("keyName") && attr.hasChildNodes() ) {
                            keyName = attr.getFirstChild().getNodeValue().trim();
                        }
                        else if( attr.getNodeName().equalsIgnoreCase("keyFingerprint") && attr.hasChildNodes() ) {
                            fingerprint = attr.getFirstChild().getNodeValue().trim();
                        }
                    }
                    if( keyName != null && fingerprint != null ) {
                        SSHKeypair keypair = new SSHKeypair();

                        keypair.setName(keyName);
                        keypair.setProviderKeypairId(keyName);
                        keypair.setFingerprint(fingerprint);
                        keypair.setProviderOwnerId(getContext().getAccountNumber());
                        keypair.setProviderRegionId(regionId);
                        keypairs.add(keypair);
                    }
                }
            }
            return keypairs;
        }
        finally {
            APITrace.end();
        }
	}
    
    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        if( action.equals(ShellKeySupport.ANY) ) {
            return new String[] { EC2Method.EC2_PREFIX + "*" };
        }
        if( action.equals(ShellKeySupport.CREATE_KEYPAIR) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.CREATE_KEY_PAIR, EC2Method.EC2_PREFIX + EC2Method.IMPORT_KEY_PAIR };
        }
        else if( action.equals(ShellKeySupport.GET_KEYPAIR) || action.equals(ShellKeySupport.LIST_KEYPAIR) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_KEY_PAIRS };
        }
        else if( action.equals(ShellKeySupport.REMOVE_KEYPAIR) ) {
            return new String[] { EC2Method.EC2_PREFIX + EC2Method.DELETE_KEY_PAIR };
        }
        return new String[0];
    }
}
