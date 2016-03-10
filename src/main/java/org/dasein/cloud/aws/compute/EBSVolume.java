/**
 * Copyright (C) 2009-2015 Dell, Inc.
 * See annotations for authorship information
 * <p/>
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.aws.compute;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.log4j.Logger;
import org.dasein.cloud.*;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.aws.model.*;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.compute.VolumeProduct;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.util.uom.storage.Gigabyte;
import org.dasein.util.uom.storage.Storage;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EBSVolume extends AbstractVolumeSupport<AWSCloud> {
    static private final Logger logger = Logger.getLogger(EBSVolume.class);

    static private final String VOLUME_PRODUCT_IOPS = "io1";

    private EBSVolumeCapabilities capabilities;

    EBSVolume( AWSCloud provider ) {
        super(provider);
    }

    @Override
    public void attach( @Nonnull String volumeId, @Nonnull String toServer, @Nullable String device ) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.attach");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.ATTACH_VOLUME);
            EC2Method method;

            parameters.put("VolumeId", volumeId);
            parameters.put("InstanceId", toServer);
            parameters.put("Device", device);
            method = new EC2Method(getProvider(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull String createVolume( @Nonnull VolumeCreateOptions options ) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.createVolume");
        try {
            if( !options.getFormat().equals(VolumeFormat.BLOCK) ) {
                throw new OperationNotSupportedException("NFS volumes are not currently supported");
            }
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.CREATE_VOLUME);
            EC2Method method;
            NodeList blocks;
            Document doc;

            if( options.getSnapshotId() != null ) {
                parameters.put("SnapshotId", options.getSnapshotId());
            }
            parameters.put("Size", String.valueOf(options.getVolumeSize().getQuantity().intValue()));

            String az = options.getDataCenterId();

            if( az == null ) {
                for( DataCenter dc : getProvider().getDataCenterServices().listDataCenters(getContext().getRegionId()) ) {
                    az = dc.getProviderDataCenterId();
                }
                if( az == null ) {
                    throw new GeneralCloudException("Unable to identify a launch data center");
                }
            }
            parameters.put("AvailabilityZone", az);
            if( getProvider().getEC2Provider().isAWS() || getProvider().getEC2Provider().isEnStratus() ) {
                if( options.getVolumeProductId() != null ) {
                    VolumeProduct prd = getVolumeProduct(options.getVolumeProductId());
                    if( prd != null ) {
                        parameters.put("VolumeType", prd.getProviderProductId());
                        if( VOLUME_PRODUCT_IOPS.equals(prd.getProviderProductId()) ) {
                            if( prd.getMaxIops() > 0 && options.getIops() > 0 ) {
                                parameters.put("Iops", String.valueOf(options.getIops()));
                            }
                            else if( prd.getMinIops() > 0 ) {
                                parameters.put("Iops", String.valueOf(prd.getMinIops()));
                            }
                        }
                    }
                }
            }
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("volumeId");
            if( blocks.getLength() > 0 ) {
                String id = blocks.item(0).getFirstChild().getNodeValue().trim();
                Map<String, Object> meta = options.getMetaData();

                meta.put("Name", options.getName());
                meta.put("Description", options.getDescription());
                List<Tag> tags = new ArrayList<>();

                for( Map.Entry<String, Object> entry : meta.entrySet() ) {
                    Object value = entry.getValue();

                    if( value != null ) {
                        tags.add(new Tag(entry.getKey(), value.toString()));
                    }
                }
                if( !tags.isEmpty() ) {
                    getProvider().createTags(EC2Method.SERVICE_ID, id, tags.toArray(new Tag[tags.size()]));
                }
                return id;
            }
            throw new GeneralCloudException("Successful POST, but no volume information was provided");
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void detach( @Nonnull String volumeId, boolean force ) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.detach");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DETACH_VOLUME);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("VolumeId", volumeId);
            if( force ) {
                parameters.put("Force", "true");
            }
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("return");
            if( blocks.getLength() > 0 ) {
                if( !blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true") ) {
                    throw new GeneralCloudException("Detach of volume denied.");
                }
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public VolumeCapabilities getCapabilities() {
        if( capabilities == null ) {
            capabilities = new EBSVolumeCapabilities(getProvider());
        }
        return capabilities;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }

    @Override
    public @Nonnull Iterable<VolumeProduct> listVolumeProducts() throws InternalException, CloudException {
        VolumeProvider volumeProvider = VolumeProvider.fromFile("/org/dasein/cloud/aws/volproducts.json", "AWS");
        String regionId = getContext().getRegionId();
        List<org.dasein.cloud.aws.model.VolumeProduct> products = volumeProvider.getProducts();
        List<VolumeProduct> volumeProducts = new ArrayList<VolumeProduct>();
        for( org.dasein.cloud.aws.model.VolumeProduct product : products ) {
            VolumePrice price = volumeProvider.findProductPrice(regionId, product.getId());
            if( price == null ) {
                continue;
            }
            VolumeProduct volumeProduct = VolumeProduct.getInstance(product.getId(), product.getName(), product.getDescription(), VolumeType.valueOf(product.getType().toUpperCase()), product.getMinIops(), product.getMaxIops(), price.getMonthly(), price.getIops());
            volumeProduct.withMaxIopsRatio(product.getIopsToGb());
            volumeProduct.withMaxVolumeSize(new Storage<>(product.getMaxSize(), Storage.GIGABYTE));
            volumeProduct.withMinVolumeSize(new Storage<>(product.getMinSize(), Storage.GIGABYTE));
            volumeProducts.add(volumeProduct);
        }
        return volumeProducts;
    }

    @Override
    public @Nullable Volume getVolume( @Nonnull String volumeId ) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.getVolume");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_VOLUMES);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("VolumeId.1", volumeId);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && ( code.startsWith("InvalidVolume.NotFound") || code.equals("InvalidParameterValue") ) ) {
                    return null;
                }
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("volumeSet");
            for( int i = 0; i < blocks.getLength(); i++ ) {
                NodeList items = blocks.item(i).getChildNodes();

                for( int j = 0; j < items.getLength(); j++ ) {
                    Node item = items.item(j);

                    if( item.getNodeName().equals("item") ) {
                        Volume volume = toVolume(item);

                        if( volume != null && volume.getProviderVolumeId().equals(volumeId) ) {
                            return volume;
                        }
                    }
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<ResourceStatus> listVolumeStatus() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.listVolumeStatus");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_VOLUMES);
            List<ResourceStatus> list = new ArrayList<>();
            EC2Method method;
            NodeList blocks;
            Document doc;

            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("volumeSet");
            for( int i = 0; i < blocks.getLength(); i++ ) {
                NodeList items = blocks.item(i).getChildNodes();

                for( int j = 0; j < items.getLength(); j++ ) {
                    Node item = items.item(j);

                    if( item.getNodeName().equals("item") ) {
                        ResourceStatus status = toStatus(item);

                        if( status != null ) {
                            list.add(status);
                        }
                    }
                }
            }
            return list;
        }
        finally {
            APITrace.end();
        }
    }


    @Override
    public @Nonnull Iterable<Volume> listVolumes() throws InternalException, CloudException {
        return listVolumes(null);
    }

    @Override
    public @Nonnull Iterable<Volume> listVolumes( @Nullable VolumeFilterOptions options ) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.listVolumes");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_VOLUMES);
            ArrayList<Volume> list = new ArrayList<>();
            EC2Method method;
            NodeList blocks;
            Document doc;

            if( options != null ) {
                AWSCloud.addExtraParameters(parameters, getProvider().getTagFilterParams(options.getTags()));
            }

            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("volumeSet");
            for( int i = 0; i < blocks.getLength(); i++ ) {
                NodeList items = blocks.item(i).getChildNodes();

                for( int j = 0; j < items.getLength(); j++ ) {
                    Node item = items.item(j);

                    if( item.getNodeName().equals("item") ) {
                        Volume volume = toVolume(item);

                        if( volume != null && ( options == null || options.matches(volume) ) ) {
                            list.add(volume);
                        }
                    }
                }
            }
            return list;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull String[] mapServiceAction( @Nonnull ServiceAction action ) {
        if( action.equals(VolumeSupport.ANY) ) {
            return new String[]{EC2Method.EC2_PREFIX + "*"};
        }
        else if( action.equals(VolumeSupport.ATTACH) ) {
            return new String[]{EC2Method.EC2_PREFIX + EC2Method.ATTACH_VOLUME};
        }
        else if( action.equals(VolumeSupport.CREATE_VOLUME) ) {
            return new String[]{EC2Method.EC2_PREFIX + EC2Method.CREATE_VOLUME};
        }
        else if( action.equals(VolumeSupport.DETACH) ) {
            return new String[]{EC2Method.EC2_PREFIX + EC2Method.DETACH_VOLUME};
        }
        else if( action.equals(VolumeSupport.GET_VOLUME) || action.equals(VolumeSupport.LIST_VOLUME) ) {
            return new String[]{EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_VOLUMES};
        }
        else if( action.equals(VolumeSupport.REMOVE_VOLUME) ) {
            return new String[]{EC2Method.EC2_PREFIX + EC2Method.DELETE_VOLUME};
        }
        return new String[0];
    }

    @Override
    public void remove( @Nonnull String volumeId ) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.remove");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DELETE_VOLUME);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("VolumeId", volumeId);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("return");
            if( blocks.getLength() > 0 ) {
                if( !blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true") ) {
                    throw new GeneralCloudException("Deletion of volume denied.");
                }
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void updateTags( @Nonnull String volumeId, @Nonnull Tag... tags ) throws CloudException, InternalException {
        updateTags(new String[]{volumeId}, tags);
    }

    @Override
    public void updateTags( @Nonnull String[] volumeIds, @Nonnull Tag... tags ) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Volume.updateTags");
        try {
            getProvider().createTags(EC2Method.SERVICE_ID, volumeIds, tags);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void removeTags( @Nonnull String volumeId, @Nonnull Tag... tags ) throws CloudException, InternalException {
        removeTags(new String[]{volumeId}, tags);
    }

    @Override
    public void removeTags( @Nonnull String[] volumeIds, @Nonnull Tag... tags ) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Volume.removeTags");
        try {
            getProvider().removeTags(EC2Method.SERVICE_ID, volumeIds, tags);
        }
        finally {
            APITrace.end();
        }
    }

    public VolumeProduct getVolumeProduct( String volumeProductId ) throws InternalException, CloudException {
        VolumeProduct prd = null;

        for( VolumeProduct p : listVolumeProducts() ) {
            if( p.getProviderProductId().equals(volumeProductId) ) {
                prd = p;
                break;
            }
        }

        return prd;
    }

    private @Nullable ResourceStatus toStatus( @Nullable Node node ) throws CloudException {
        if( node == null ) {
            return null;
        }
        NodeList attrs = node.getChildNodes();
        VolumeState state = VolumeState.PENDING;
        String volumeId = null;

        for( int i = 0; i < attrs.getLength(); i++ ) {
            Node attr = attrs.item(i);
            String name;

            name = attr.getNodeName();
            if( name.equals("volumeId") ) {
                volumeId = attr.getFirstChild().getNodeValue().trim();
            }
            else if( name.equals("status") ) {
                String s = attr.getFirstChild().getNodeValue().trim();

                if( s.equals("creating") || s.equals("attaching") || s.equals("attached") || s.equals("detaching") || s.equals("detached") ) {
                    state = VolumeState.PENDING;
                }
                else if( s.equals("available") || s.equals("in-use") ) {
                    state = VolumeState.AVAILABLE;
                }
                else {
                    state = VolumeState.DELETED;
                }
            }
        }
        if( volumeId == null ) {
            return null;
        }
        return new ResourceStatus(volumeId, state);
    }

    private @Nullable Volume toVolume( @Nullable Node node ) throws CloudException, InternalException {
        if( node == null ) {
            return null;
        }
        NodeList attrs = node.getChildNodes();
        Volume volume = new Volume();

        volume.setProviderProductId("standard");
        volume.setType(VolumeType.HDD);
        volume.setFormat(VolumeFormat.BLOCK);
        for( int i = 0; i < attrs.getLength(); i++ ) {
            Node attr = attrs.item(i);
            String name;

            name = attr.getNodeName();
            if( name.equals("volumeId") ) {
                volume.setProviderVolumeId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( name.equalsIgnoreCase("name") && attr.hasChildNodes() ) {
                volume.setName(attr.getFirstChild().getNodeName().trim());
            }
            else if( name.equalsIgnoreCase("description") && attr.hasChildNodes() ) {
                volume.setDescription(attr.getFirstChild().getNodeName().trim());
            }
            else if( name.equals("size") ) {
                int size = Integer.parseInt(attr.getFirstChild().getNodeValue().trim());

                volume.setSize(new Storage<>(size, Storage.GIGABYTE));
            }
            else if( name.equals("snapshotId") ) {
                NodeList values = attr.getChildNodes();

                if( values != null && values.getLength() > 0 ) {
                    volume.setProviderSnapshotId(values.item(0).getNodeValue().trim());
                }
            }
            else if( name.equals("availabilityZone") ) {
                String zoneId = attr.getFirstChild().getNodeValue().trim();

                volume.setProviderDataCenterId(zoneId);
            }
            else if( name.equalsIgnoreCase("volumeType") && attr.hasChildNodes() ) {
                volume.setProviderProductId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( name.equalsIgnoreCase("iops") && attr.hasChildNodes() ) {
                volume.setIops(Integer.parseInt(attr.getFirstChild().getNodeValue().trim()));
            }
            else if( name.equals("createTime") ) {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                String value = attr.getFirstChild().getNodeValue().trim();

                try {
                    volume.setCreationTimestamp(fmt.parse(value).getTime());
                }
                catch( ParseException e ) {
                    logger.error(e);
                    throw new GeneralCloudException(e);
                }
            }
            else if( name.equals("status") ) {
                volume.setCurrentState(toVolumeState(attr));
            }
            else if( name.equals("tagSet") ) {
                getProvider().setTags(attr, volume);

                String s = volume.getTag("Name");

                if( s != null && volume.getName() == null ) {
                    volume.setName(s);
                }
                s = volume.getTag("Description");

                if( s != null && volume.getDescription() == null ) {
                    volume.setDescription(s);
                }
            }
            else if( name.equals("attachmentSet") ) {
                NodeList attachments = attr.getChildNodes();

                for( int j = 0; j < attachments.getLength(); j++ ) {
                    Node item = attachments.item(j);

                    if( item.getNodeName().equals("item") ) {
                        NodeList infoList = item.getChildNodes();

                        for( int k = 0; k < infoList.getLength(); k++ ) {
                            Node info = infoList.item(k);

                            name = info.getNodeName();
                            if( name.equals("instanceId") ) {
                                volume.setProviderVirtualMachineId(info.getFirstChild().getNodeValue().trim());
                            }
                            else if( name.equals("device") ) {
                                String deviceId = info.getFirstChild().getNodeValue().trim();

                                if( deviceId.startsWith("unknown,requested:") ) {
                                    deviceId = deviceId.substring(18);
                                }
                                volume.setDeviceId(deviceId);
                            }
                        }
                    }
                }
            }
        }
        if( volume.getProviderVolumeId() == null ) {
            return null;
        }
        if( volume.getName() == null ) {
            volume.setName(volume.getProviderVolumeId());
        }
        if( volume.getDescription() == null ) {
            volume.setDescription(volume.getName());
        }
        volume.setProviderRegionId(getContext().getRegionId());
        return volume;
    }

    static public @Nullable VolumeState toVolumeState( Node node ) {
        String s = AWSCloud.getTextValue(node);
        VolumeState state;

        switch( s ) {
            case "creating":
            case "attaching":
            case "attached":
            case "detaching":
            case "detached":
                state = VolumeState.PENDING;
                break;
            case "available":
            case "in-use":
                state = VolumeState.AVAILABLE;
                break;
            default:
                state = VolumeState.DELETED;
                break;
        }
        return state;
    }
}
