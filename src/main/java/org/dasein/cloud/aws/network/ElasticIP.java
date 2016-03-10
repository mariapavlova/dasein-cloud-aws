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

package org.dasein.cloud.aws.network;

import org.apache.http.conn.util.InetAddressUtils;
import org.apache.log4j.Logger;
import org.dasein.cloud.*;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.aws.compute.EC2Exception;
import org.dasein.cloud.aws.compute.EC2Method;
import org.dasein.cloud.compute.ComputeServices;
import org.dasein.cloud.compute.VirtualMachine;
import org.dasein.cloud.compute.VirtualMachineSupport;
import org.dasein.cloud.compute.VmState;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.network.*;
import org.dasein.cloud.util.APITrace;
import org.dasein.util.CalendarWrapper;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;

public class ElasticIP extends AbstractIpAddressSupport<AWSCloud> {
    static private final Logger logger = AWSCloud.getLogger(ElasticIP.class);

    static private final ExecutorService threadPool = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dasein-ip-pool");
            t.setDaemon(true);
            return t;
        }
    });
    private transient volatile ElasticIPAddressCapabilities capabilities;

    ElasticIP(AWSCloud provider) {
        super(provider);
    }

    private
    @Nullable
    VirtualMachine getInstance(@Nonnull String instanceId) throws InternalException, CloudException {
        ComputeServices services = getProvider().getComputeServices();

        if (services != null) {
            VirtualMachineSupport support = services.getVirtualMachineSupport();

            if (support != null) {
                return support.getVirtualMachine(instanceId);
            }
        }
        throw new GeneralCloudException("Instances are not supported in " + getProvider().getCloudName());
    }

    @Override
    public void assign(@Nonnull String addressId, @Nonnull String instanceId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.assignAddressToServer");
        try {
            long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 20L);
            VirtualMachine vm = getInstance(instanceId);

            while (System.currentTimeMillis() < timeout) {
                if (vm == null || VmState.TERMINATED.equals(vm.getCurrentState())) {
                    throw new IllegalArgumentException("There is no such virtual machine '" + instanceId + "'");
                }
                VmState s = vm.getCurrentState();

                if (VmState.RUNNING.equals(s) || VmState.STOPPED.equals(s) || VmState.PAUSED.equals(s) || VmState.SUSPENDED.equals(s)) {
                    break;
                }
                try {
                    Thread.sleep(20000L);
                } catch (InterruptedException ignore) {
                }
                try {
                    vm = getInstance(instanceId);
                } catch (Throwable ignore) {
                }
            }
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.ASSOCIATE_ADDRESS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            setId("", parameters, addressId, false);
            parameters.put("InstanceId", instanceId);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("return");
            if (blocks.getLength() > 0) {
                if (!blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true")) {
                    throw new GeneralCloudException("Association of address denied.");
                }
            }
        } finally {
            APITrace.end();
        }
    }

    @Override
    public void assignToNetworkInterface(@Nonnull String addressId, @Nonnull String nicId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.assignAddressToNetworkInterface");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.ASSOCIATE_ADDRESS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("AllocationId", addressId);
            parameters.put("NetworkInterfaceId", nicId);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("return");
            if (blocks.getLength() > 0) {
                if (!blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true")) {
                    throw new GeneralCloudException("Association of address denied.");
                }
            }
        } finally {
            APITrace.end();
        }
    }

    @Override
    public
    @Nonnull
    String forward(@Nonnull String addressId, int publicPort, @Nonnull Protocol protocol, int privatePort, @Nonnull String serverId) throws InternalException, CloudException {
        throw new OperationNotSupportedException();
    }

    @Nonnull
    @Override
    public IPAddressCapabilities getCapabilities() throws CloudException, InternalException {
        if (capabilities == null) {
            capabilities = new ElasticIPAddressCapabilities(getProvider());
        }
        return capabilities;
    }

    @Override
    public
    @Nullable
    IpAddress getIpAddress(@Nonnull String addressId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.getIpAddress");
        try {
            if (isIPAddress(addressId)) {
                return getEC2Address(addressId);
            }

            return getVPCAddress(addressId);
        } finally {
            APITrace.end();
        }
    }

    private
    @Nullable
    IpAddress getEC2Address(@Nonnull String addressId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.getEC2Address");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_ADDRESSES);
            IpAddress address = null;
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("PublicIp.1", addressId);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                String code = e.getCode();

                if (code != null && (code.equals("InvalidAllocationID.NotFound")
                        || code.equals("InvalidAddress.NotFound")
                        || e.getMessage().contains("Invalid value"))) {
                    return null;
                }
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("addressesSet");
            for (int i = 0; i < blocks.getLength(); i++) {
                NodeList items = blocks.item(i).getChildNodes();

                for (int j = 0; j < items.getLength(); j++) {
                    Node item = items.item(j);

                    if (item.getNodeName().equals("item")) {
                        address = toAddress(getContext(), item);
                        // In case of EC2 the addressId should be the actual "x.x.x.x" address,
                        // and not the "eipalloc-XXXX" since latter simply not available.
                        if (address != null && addressId.equals(address.getRawAddress().getIpAddress())) {
                            return address;
                        }
                    }
                }
            }
            return address;
        } finally {
            APITrace.end();
        }
    }

    private
    @Nullable
    IpAddress getVPCAddress(@Nonnull String addressId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.getVPCAddress");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_ADDRESSES);
            IpAddress address = null;
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("AllocationId.1", addressId);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                String code = e.getCode();

                if (code != null && (code.equals("InvalidAllocationID.NotFound") || code.equals("InvalidAddress.NotFound") || e.getMessage().contains("Invalid value") || e.getMessage().startsWith("InvalidAllocation"))) {
                    return null;
                }
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("addressesSet");
            for (int i = 0; i < blocks.getLength(); i++) {
                NodeList items = blocks.item(i).getChildNodes();

                for (int j = 0; j < items.getLength(); j++) {
                    Node item = items.item(j);

                    if (item.getNodeName().equals("item")) {
                        address = toAddress(getContext(), item);
                        if (address != null && addressId.equals(address.getProviderIpAddressId())) {
                            return address;
                        }
                    }
                }
            }
            return address;
        } finally {
            APITrace.end();
        }
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }

    @Override
    public
    @Nonnull
    Iterable<IpAddress> listPrivateIpPool(boolean unassignedOnly) throws InternalException, CloudException {
        return Collections.emptyList();
    }

    @Override
    public
    @Nonnull
    Iterable<IpAddress> listPublicIpPool(boolean unassignedOnly) throws InternalException, CloudException {
        return listIpPool(IPVersion.IPV4, unassignedOnly);
    }

    @Override
    public
    @Nonnull
    Iterable<IpAddress> listIpPool(@Nonnull IPVersion version, boolean unassignedOnly) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.listIpPool");
        try {
            Future<Iterable<IpAddress>> ipPoolFuture = listIpPoolConcurrently(version, unassignedOnly);
            return ipPoolFuture.get();
        } catch (CloudException ce) {
            throw ce;
        } catch (Exception e) {
            throw new InternalException(e);
        } finally {
            APITrace.end();
        }
    }

    public Future<Iterable<IpAddress>> listIpPoolConcurrently(IPVersion version, boolean unassignedOnly) throws CloudException, InternalException {
        return threadPool.submit(
                new ListIpPoolCallable(
                        version,
                        unassignedOnly
                )
        );
    }

    public class ListIpPoolCallable implements Callable<Iterable<IpAddress>> {
        IPVersion version;
        boolean unassignedOnly;

        public ListIpPoolCallable(IPVersion version, boolean unassignedOnly) {
            this.version = version;
            this.unassignedOnly = unassignedOnly;
        }

        public Iterable<IpAddress> call() throws CloudException, InternalException {
            if (!version.equals(IPVersion.IPV4)) {
                return Collections.emptyList();
            }
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_ADDRESSES);
            ArrayList<IpAddress> list = new ArrayList<IpAddress>();
            EC2Method method;
            NodeList blocks;
            Document doc;

            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("addressesSet");
            for (int i = 0; i < blocks.getLength(); i++) {
                NodeList items = blocks.item(i).getChildNodes();

                for (int j = 0; j < items.getLength(); j++) {
                    Node item = items.item(j);

                    if (item.getNodeName().equals("item")) {
                        IpAddress address = toAddress(getContext(), item);
                        if( address == null || !version.equals(address.getVersion()) ) {
                            continue;
                        }
                        if (!unassignedOnly || (address.getServerId() == null && address.getProviderLoadBalancerId() == null)) {
                            list.add(address);
                        }
                    }
                }
            }
            return list;
        }
    }

    @Override
    public
    @Nonnull
    Iterable<ResourceStatus> listIpPoolStatus(@Nonnull IPVersion version) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.listIpPoolStatus");
        try {
            if (!version.equals(IPVersion.IPV4)) {
                return Collections.emptyList();
            }
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DESCRIBE_ADDRESSES);
            ArrayList<ResourceStatus> list = new ArrayList<ResourceStatus>();
            EC2Method method;
            NodeList blocks;
            Document doc;

            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("addressesSet");
            for (int i = 0; i < blocks.getLength(); i++) {
                NodeList items = blocks.item(i).getChildNodes();

                for (int j = 0; j < items.getLength(); j++) {
                    Node item = items.item(j);

                    if (item.getNodeName().equals("item")) {
                        ResourceStatus status = toStatus(item);

                        if (status != null) {
                            list.add(status);
                        }
                    }
                }
            }
            return list;
        } finally {
            APITrace.end();
        }
    }

    @Override
    public
    @Nonnull
    Collection<IpForwardingRule> listRules(@Nonnull String addressId) throws InternalException, CloudException {
        return Collections.emptyList();
    }

    @Override
    public
    @Nonnull
    String[] mapServiceAction(@Nonnull ServiceAction action) {
        if (action.equals(IpAddressSupport.ANY)) {
            return new String[]{EC2Method.EC2_PREFIX + "*"};
        } else if (action.equals(IpAddressSupport.ASSIGN)) {
            return new String[]{EC2Method.EC2_PREFIX + EC2Method.ASSOCIATE_ADDRESS};
        } else if (action.equals(IpAddressSupport.CREATE_IP_ADDRESS)) {
            return new String[]{EC2Method.EC2_PREFIX + EC2Method.ALLOCATE_ADDRESS};
        } else if (action.equals(IpAddressSupport.FORWARD)) {
            return new String[0];
        } else if (action.equals(IpAddressSupport.GET_IP_ADDRESS)) {

            return new String[]{EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_ADDRESSES};
        } else if (action.equals(IpAddressSupport.LIST_IP_ADDRESS)) {

            return new String[]{EC2Method.EC2_PREFIX + EC2Method.DESCRIBE_ADDRESSES};
        } else if (action.equals(IpAddressSupport.RELEASE)) {
            return new String[]{EC2Method.EC2_PREFIX + EC2Method.DISASSOCIATE_ADDRESS};
        } else if (action.equals(IpAddressSupport.REMOVE_IP_ADDRESS)) {
            return new String[]{EC2Method.EC2_PREFIX + EC2Method.RELEASE_ADDRESS};
        } else if (action.equals(IpAddressSupport.STOP_FORWARD)) {
            return new String[0];
        }
        return new String[0];
    }

    @Override
    public void releaseFromServer(@Nonnull String addressId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.releaseFromServer");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.DISASSOCIATE_ADDRESS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            if (!isIPAddress(addressId)) {
                // If releasing an addressId (eipalloc-xxx) from a VM,
                // we need to look up its associationId (eipassoc-xxx)
                String assocId = getVPCAddress(addressId).getProviderAssociationId();
                if (assocId == null) {
                    throw new GeneralCloudException("Address " + addressId + " is not associated with any server.");
                }
                addressId = assocId;
            }
            setId("", parameters, addressId, true);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("return");
            if (blocks.getLength() > 0) {
                if (!blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true")) {
                    throw new GeneralCloudException("Release of address denied.");
                }
            }
        } finally {
            APITrace.end();
        }
    }

    /**
     * Identify if the string is an IP address, e.g. x.x.x.x or x:x:x:x:x:x:x:x
     * @param address IP address to test
     * @return true if parameter is a normal IPv4 or IPv6 address
     */
    private boolean isIPAddress(@Nonnull String address) {
        return (InetAddressUtils.isIPv4Address(address) || InetAddressUtils.isIPv6Address(address));
    }

    private void setId(@Nonnull String postfix, @Nonnull Map<String, String> parameters, @Nonnull String addressId,
                       @Nullable Boolean disassociate) throws CloudException, InternalException {
        if (isIPAddress(addressId)) {
            parameters.put("PublicIp" + postfix, addressId);
        } else {
            if (disassociate != null && disassociate) {
                parameters.put("AssociationId" + postfix, addressId);
            } else {
                parameters.put("AllocationId" + postfix, addressId);
            }
        }
    }

    @Override
    public void releaseFromPool(@Nonnull String addressId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.releaseFromPool");
        try {
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.RELEASE_ADDRESS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            setId("", parameters, addressId, false);
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("return");
            if (blocks.getLength() > 0) {
                if (!blocks.item(0).getFirstChild().getNodeValue().equalsIgnoreCase("true")) {
                    throw new GeneralCloudException("Deletion of address denied.");
                }
            }
        } finally {
            APITrace.end();
        }
    }

    @Override
    public
    @Nonnull
    String request(@Nonnull AddressType betterBePublic) throws InternalException, CloudException {
        if (!betterBePublic.equals(AddressType.PUBLIC)) {
            throw new OperationNotSupportedException("AWS supports only public IP address requests.");
        }
        return request(IPVersion.IPV4);
    }

    @Override
    public
    @Nonnull
    String request(@Nonnull IPVersion version) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.request");
        try {
            if (!version.equals(IPVersion.IPV4)) {
                throw new OperationNotSupportedException(getProvider().getCloudName() + " does not support " + version);
            }
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.ALLOCATE_ADDRESS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }

            // First, let's see if there's a VPC-style id there
            blocks = doc.getElementsByTagName("allocationId");
            if (blocks.getLength() > 0) {
                return blocks.item(0).getFirstChild().getNodeValue().trim();
            }

            // Otherwise, it's probably the case of EC2-Classic, so an IP address will be our addressId
            blocks = doc.getElementsByTagName("publicIp");
            if (blocks.getLength() > 0) {
                return blocks.item(0).getFirstChild().getNodeValue().trim();
            }

            throw new GeneralCloudException("Unable to create an address.");
        } finally {
            APITrace.end();
        }
    }

    @Override
    public
    @Nonnull
    String requestForVLAN(@Nonnull IPVersion forVersion) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "IpAddress.requestForVLAN");
        try {
            if (!forVersion.equals(IPVersion.IPV4)) {
                throw new OperationNotSupportedException(getProvider().getCloudName() + " does not support " + forVersion + ".");
            }
            Map<String, String> parameters = getProvider().getStandardParameters(EC2Method.ALLOCATE_ADDRESS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("Domain", "vpc");
            method = new EC2Method(getProvider(), parameters);
            try {
                doc = method.invoke();
            } catch (EC2Exception e) {
                logger.error(e.getSummary());
                throw new GeneralCloudException(e);
            }
            blocks = doc.getElementsByTagName("allocationId");
            if (blocks.getLength() > 0) {
                return blocks.item(0).getFirstChild().getNodeValue().trim();
            }
            throw new GeneralCloudException("Unable to create an address.");
        } finally {
            APITrace.end();
        }
    }

    @Override
    public
    @Nonnull
    String requestForVLAN(@Nonnull IPVersion version, @Nonnull String vlanId) throws CloudException, InternalException {
        throw new OperationNotSupportedException("AWS IP addresses may not be assigned to a specific VLAN");
    }

    @Override
    public void stopForward(@Nonnull String ruleId) throws InternalException, CloudException {
        throw new OperationNotSupportedException();
    }

    private
    @Nullable
    IpAddress toAddress(@Nonnull ProviderContext ctx, @Nullable Node node) throws CloudException {
        if (node == null) {
            return null;
        }
        String regionId = ctx.getRegionId();

        if (regionId == null) {
            throw new GeneralCloudException("No regionID was set in context");
        }
        NodeList attrs = node.getChildNodes();
        IpAddress address = new IpAddress();
        String instanceId = null, ip = null;
        String ipAddressId = null, nicId = null, associationId = null;
        boolean forVlan = false;

        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String name;

            name = attr.getNodeName();
            if (name.equals("publicIp")) {
                ip = attr.getFirstChild().getNodeValue().trim();
            } else if (name.equals("instanceId")) {
                if (attr.getChildNodes().getLength() > 0) {
                    Node id = attr.getFirstChild();

                    if (id != null) {
                        String value = id.getNodeValue();

                        if (value != null) {
                            value = value.trim();
                            if (value.length() > 0) {
                                instanceId = value;
                            }
                        }
                    }
                }
            } else if (name.equals("allocationId") && attr.hasChildNodes()) {
                ipAddressId = attr.getFirstChild().getNodeValue().trim();
            } else if (name.equals("associationId") && attr.hasChildNodes()) {
                associationId = attr.getFirstChild().getNodeValue().trim();
            } else if (name.equals("domain") && attr.hasChildNodes()) {
                forVlan = attr.getFirstChild().getNodeValue().trim().equalsIgnoreCase("vpc");
                if (!forVlan) {
                    address.setAddressType(AddressType.PUBLIC);
                } else {
                    address.setAddressType(AddressType.PRIVATE);
                }
            } else if (name.equals("networkInterfaceId") && attr.hasChildNodes()) {
                nicId = attr.getFirstChild().getNodeValue().trim();
            }
        }
        if (ip == null) {
            throw new GeneralCloudException("Invalid address data, no IP.");
        }
        if (ipAddressId == null) {
            ipAddressId = ip;
        }
        address.setVersion(IPVersion.IPV4);
        address.setAddressType(AddressType.PUBLIC);
        address.setAddress(ip);
        address.setIpAddressId(ipAddressId);
        address.setRegionId(regionId);
        address.setForVlan(forVlan);
        address.setProviderAssociationId(associationId);
        address.setProviderNetworkInterfaceId(nicId);
        if (instanceId != null && getProvider().getEC2Provider().isEucalyptus()) {
            if (instanceId.startsWith("available")) {
                instanceId = null;
            } else {
                int idx = instanceId.indexOf(' ');
                if (idx > 0) {
                    instanceId = instanceId.substring(0, idx);
                }
            }
        }
        address.setServerId(instanceId);
        return address;
    }

    private
    @Nullable
    ResourceStatus toStatus(@Nullable Node node) throws CloudException {
        if (node == null) {
            return null;
        }
        NodeList attrs = node.getChildNodes();
        String instanceId = null, ipAddressId = null, nicId = null, ip = null;
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String name;
            name = attr.getNodeName();
            if (name.equals("publicIp")) {
                ip = attr.getFirstChild().getNodeValue().trim();
            } else if (name.equals("instanceId")) {
                if (attr.getChildNodes().getLength() > 0) {
                    Node id = attr.getFirstChild();
                    if (id != null) {
                        String value = id.getNodeValue();
                        if (value != null) {
                            value = value.trim();
                            if (value.length() > 0) {
                                instanceId = value;
                            }
                        }
                    }
                }
            } else if (name.equals("allocationId") && attr.hasChildNodes()) {
                ipAddressId = attr.getFirstChild().getNodeValue().trim();
            } else if (name.equals("networkInterfaceId") && attr.hasChildNodes()) {
                nicId = attr.getFirstChild().getNodeValue().trim();
            }
        }
        if (ipAddressId == null) {
            ipAddressId = ip;
        }
        if (ipAddressId == null) {
            return null;
        }
        return new ResourceStatus(ipAddressId, instanceId == null && nicId == null);
    }
}
