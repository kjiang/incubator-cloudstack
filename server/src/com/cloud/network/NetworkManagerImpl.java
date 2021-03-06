// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
// 
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network;

import java.net.URI;
import java.security.InvalidParameterException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.acl.ControlledEntity.ACLType;
import com.cloud.acl.SecurityChecker.AccessType;
import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.alert.AlertManager;
import com.cloud.api.commands.CreateNetworkCmd;
import com.cloud.api.commands.ListNetworksCmd;
import com.cloud.api.commands.ListTrafficTypeImplementorsCmd;
import com.cloud.api.commands.RestartNetworkCmd;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Pod;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.AccountLimitException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapcityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.IpAddress.State;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.AddressFormat;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork.BroadcastDomainRange;
import com.cloud.network.VirtualRouterProvider.VirtualRouterProviderType;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.element.ConnectivityProvider;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.FirewallServiceProvider;
import com.cloud.network.element.SourceNatServiceProvider;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkACLServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.PortForwardingServiceProvider;
import com.cloud.network.element.RemoteAccessVPNServiceProvider;
import com.cloud.network.element.Site2SiteVpnServiceProvider;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.network.element.VirtualRouterElement;
import com.cloud.network.element.VpcVirtualRouterElement;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.lb.LoadBalancingRule.LbStickinessPolicy;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.rules.StaticNatRule;
import com.cloud.network.rules.StaticNatRuleImpl;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.NetworkACLManager;
import com.cloud.network.vpc.PrivateIpVO;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpn.RemoteAccessVpnService;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offerings.NetworkOfferingServiceMapVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.org.Grouping;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.server.ResourceTag.TaggedResourceType;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.DomainManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserContext;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.AnnotationHelper;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Adapters;
import com.cloud.utils.component.Inject;
import com.cloud.utils.component.Manager;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import edu.emory.mathcs.backport.java.util.Collections;

/**
 * NetworkManagerImpl implements NetworkManager.
 */
@Local(value = { NetworkManager.class, NetworkService.class })
public class NetworkManagerImpl implements NetworkManager, NetworkService, Manager, Listener {
    private static final Logger s_logger = Logger.getLogger(NetworkManagerImpl.class);

    String _name;
    @Inject
    DataCenterDao _dcDao = null;
    @Inject
    VlanDao _vlanDao = null;
    @Inject
    IPAddressDao _ipAddressDao = null;
    @Inject
    AccountDao _accountDao = null;
    @Inject
    DomainDao _domainDao = null;
    @Inject
    UserStatisticsDao _userStatsDao = null;
    @Inject
    EventDao _eventDao = null;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    UserVmDao _userVmDao = null;
    @Inject
    AlertManager _alertMgr;
    @Inject
    AccountManager _accountMgr;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    AccountVlanMapDao _accountVlanMapDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao = null;
    @Inject
    NetworkDao _networksDao = null;
    @Inject
    NicDao _nicDao = null;
    @Inject
    RulesManager _rulesMgr;
    @Inject
    LoadBalancingRulesManager _lbMgr;
    @Inject
    UsageEventDao _usageEventDao;
    @Inject
    RemoteAccessVpnService _vpnMgr;
    @Inject
    PodVlanMapDao _podVlanMapDao;
    @Inject(adapter = NetworkGuru.class)
    Adapters<NetworkGuru> _networkGurus;
    @Inject(adapter = NetworkElement.class)
    Adapters<NetworkElement> _networkElements;
    @Inject
    NetworkDomainDao _networkDomainDao;
    @Inject
    VMInstanceDao _vmDao;
    @Inject
    FirewallManager _firewallMgr;
    @Inject
    FirewallRulesDao _firewallDao;
    @Inject
    PortForwardingRulesDao _portForwardingDao;
    @Inject
    ResourceLimitService _resourceLimitMgr;
    @Inject
    DomainRouterDao _routerDao;
    @Inject
    DomainManager _domainMgr;
    @Inject
    ProjectManager _projectMgr;
    @Inject
    NetworkOfferingServiceMapDao _ntwkOfferingSrvcDao;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    PhysicalNetworkServiceProviderDao _pNSPDao;
    @Inject
    PortForwardingRulesDao _portForwardingRulesDao;
    @Inject
    LoadBalancerDao _lbDao;
    @Inject
    PhysicalNetworkTrafficTypeDao _pNTrafficTypeDao;
    @Inject
    AgentManager _agentMgr;
    @Inject
    HostDao _hostDao;
    @Inject
    NetworkServiceMapDao _ntwkSrvcDao;
    @Inject
    StorageNetworkManager _stnwMgr;
    @Inject
    VpcManager _vpcMgr;
    @Inject
    PrivateIpDao _privateIpDao;
    @Inject
    NetworkACLManager _networkACLMgr;
    @Inject
    ResourceTagDao _resourceTagDao;

    private final HashMap<String, NetworkOfferingVO> _systemNetworks = new HashMap<String, NetworkOfferingVO>(5);
    private static Long _privateOfferingId = null;

    ScheduledExecutorService _executor;

    SearchBuilder<AccountVO> AccountsUsingNetworkSearch;
    SearchBuilder<IPAddressVO> AssignIpAddressSearch;
    SearchBuilder<IPAddressVO> AssignIpAddressFromPodVlanSearch;
    SearchBuilder<IPAddressVO> IpAddressSearch;
    SearchBuilder<NicVO> NicForTrafficTypeSearch;

    int _networkGcWait;
    int _networkGcInterval;
    String _networkDomain;
    int _cidrLimit;
    boolean _allowSubdomainNetworkAccess;
    int _networkLockTimeout;

    private Map<String, String> _configs;

    HashMap<Long, Long> _lastNetworkIdsToFree = new HashMap<Long, Long>();

    private static HashMap<Service, List<Provider>> s_serviceToImplementedProvidersMap = new HashMap<Service, List<Provider>>();
    private static HashMap<String, String> s_providerToNetworkElementMap = new HashMap<String, String>();

    @Override
    public NetworkElement getElementImplementingProvider(String providerName) {
        String elementName = s_providerToNetworkElementMap.get(providerName);
        NetworkElement element = _networkElements.get(elementName);
        return element;
    }

    @Override
    public List<Service> getElementServices(Provider provider) {
        NetworkElement element = getElementImplementingProvider(provider.getName());
        if (element == null) {
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getName() + "'");
        }
        return new ArrayList<Service>(element.getCapabilities().keySet());
    }

    @Override
    public boolean canElementEnableIndividualServices(Provider provider) {
        NetworkElement element = getElementImplementingProvider(provider.getName());
        if (element == null) {
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getName() + "'");
        }
        return element.canEnableIndividualServices();
    }

    @Override
    public PublicIp assignPublicIpAddress(long dcId, Long podId, Account owner, VlanType type, Long networkId, String requestedIp, boolean isSystem) throws InsufficientAddressCapacityException {
        return fetchNewPublicIp(dcId, podId, null, owner, type, networkId, false, true, requestedIp, isSystem, null);
    }

    @DB
    public PublicIp fetchNewPublicIp(long dcId, Long podId, Long vlanDbId, Account owner, VlanType vlanUse, 
            Long guestNetworkId, boolean sourceNat, boolean assign, String requestedIp, boolean isSystem, Long vpcId)
            throws InsufficientAddressCapacityException {
        StringBuilder errorMessage = new StringBuilder("Unable to get ip adress in ");
        Transaction txn = Transaction.currentTxn();
        txn.start();
        SearchCriteria<IPAddressVO> sc = null;
        if (podId != null) {
            sc = AssignIpAddressFromPodVlanSearch.create();
            sc.setJoinParameters("podVlanMapSB", "podId", podId);
            errorMessage.append(" pod id=" + podId);
        } else {
            sc = AssignIpAddressSearch.create();
            errorMessage.append(" zone id=" + dcId);
        }

        if (vlanDbId != null) {
            sc.addAnd("vlanId", SearchCriteria.Op.EQ, vlanDbId);
            errorMessage.append(", vlanId id=" + vlanDbId);
        }

        sc.setParameters("dc", dcId);

        DataCenter zone = _configMgr.getZone(dcId);

        // for direct network take ip addresses only from the vlans belonging to the network
        if (vlanUse == VlanType.DirectAttached) {
            sc.setJoinParameters("vlan", "networkId", guestNetworkId);
            errorMessage.append(", network id=" + guestNetworkId);
        }
        sc.setJoinParameters("vlan", "type", vlanUse);

        if (requestedIp != null) {
            sc.addAnd("address", SearchCriteria.Op.EQ, requestedIp);
            errorMessage.append(": requested ip " + requestedIp + " is not available");
        }

        Filter filter = new Filter(IPAddressVO.class, "vlanId", true, 0l, 1l);

        List<IPAddressVO> addrs = _ipAddressDao.lockRows(sc, filter, true);

        if (addrs.size() == 0) {
            if (podId != null) {
                InsufficientAddressCapacityException ex = new InsufficientAddressCapacityException
                        ("Insufficient address capacity", Pod.class, podId);
                // for now, we hardcode the table names, but we should ideally do a lookup for the tablename from the VO object.
                ex.addProxyObject("Pod", podId, "podId");                
                throw ex;
            }
            s_logger.warn(errorMessage.toString());
            InsufficientAddressCapacityException ex = new InsufficientAddressCapacityException
                    ("Insufficient address capacity", DataCenter.class, dcId);
            ex.addProxyObject("data_center", dcId, "dcId");
            throw ex;
        }

        assert (addrs.size() == 1) : "Return size is incorrect: " + addrs.size();

        IPAddressVO addr = addrs.get(0);
        addr.setSourceNat(sourceNat);
        addr.setAllocatedTime(new Date());
        addr.setAllocatedInDomainId(owner.getDomainId());
        addr.setAllocatedToAccountId(owner.getId());
        addr.setSystem(isSystem);

        if (assign) {
            markPublicIpAsAllocated(addr);
        } else {
            addr.setState(IpAddress.State.Allocating);
        }
        addr.setState(assign ? IpAddress.State.Allocated : IpAddress.State.Allocating);

        if (vlanUse != VlanType.DirectAttached || zone.getNetworkType() == NetworkType.Basic) {
            addr.setAssociatedWithNetworkId(guestNetworkId);
            addr.setVpcId(vpcId);
        }

        _ipAddressDao.update(addr.getId(), addr);

        txn.commit();

        if (vlanUse == VlanType.VirtualNetwork) {
            _firewallMgr.addSystemFirewallRules(addr, owner);
        }

        long macAddress = NetUtils.createSequenceBasedMacAddress(addr.getMacAddress());

        return new PublicIp(addr, _vlanDao.findById(addr.getVlanId()), macAddress);
    }

    @DB
    @Override
    public void markPublicIpAsAllocated(IPAddressVO addr) {

        assert (addr.getState() == IpAddress.State.Allocating || addr.getState() == IpAddress.State.Free) :
            "Unable to transition from state " + addr.getState() + " to " + IpAddress.State.Allocated;

        Transaction txn = Transaction.currentTxn();

        Account owner = _accountMgr.getAccount(addr.getAllocatedToAccountId());

        txn.start();
        addr.setState(IpAddress.State.Allocated);
        _ipAddressDao.update(addr.getId(), addr);

        // Save usage event
        if (owner.getAccountId() != Account.ACCOUNT_ID_SYSTEM) {
            VlanVO vlan = _vlanDao.findById(addr.getVlanId());

            String guestType = vlan.getVlanType().toString();
            
            UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_NET_IP_ASSIGN, owner.getId(), 
                    addr.getDataCenterId(), addr.getId(), addr.getAddress().toString(), addr.isSourceNat(), guestType, 
                    addr.getSystem());
            _usageEventDao.persist(usageEvent);
            // don't increment resource count for direct ip addresses
            if (addr.getAssociatedWithNetworkId() != null) {
                _resourceLimitMgr.incrementResourceCount(owner.getId(), ResourceType.public_ip);
            }
        }

        txn.commit();
    }

    
    @Override
    public PublicIp assignSourceNatIpAddressToGuestNetwork(Account owner, Network guestNetwork) 
            throws InsufficientAddressCapacityException, ConcurrentOperationException {
        assert (guestNetwork.getTrafficType() != null) : "You're asking for a source nat but your network " +
                "can't participate in source nat.  What do you have to say for yourself?";
        long dcId = guestNetwork.getDataCenterId();

        IPAddressVO sourceNatIp = getExistingSourceNatInNetwork(owner.getId(), guestNetwork.getId());

        PublicIp ipToReturn = null;
        if (sourceNatIp != null) {
            ipToReturn = new PublicIp(sourceNatIp, _vlanDao.findById(sourceNatIp.getVlanId()), 
                    NetUtils.createSequenceBasedMacAddress(sourceNatIp.getMacAddress()));
        } else {
            ipToReturn = assignDedicateIpAddress(owner, guestNetwork.getId(), null, dcId, true);
        }
        
        return ipToReturn;
    }
    
    @Override
    public PublicIp assignVpnGatewayIpAddress(long dcId, Account owner, long vpcId) throws InsufficientAddressCapacityException, ConcurrentOperationException {
        return assignDedicateIpAddress(owner, null, vpcId, dcId, false);
    }
    

    @DB
    @Override
    public PublicIp assignDedicateIpAddress(Account owner, Long guestNtwkId, Long vpcId, long dcId, boolean isSourceNat) 
            throws ConcurrentOperationException, InsufficientAddressCapacityException {

        long ownerId = owner.getId();
        
        // Check that the maximum number of public IPs for the given accountId will not be exceeded
        try {
            _resourceLimitMgr.checkResourceLimit(owner, ResourceType.public_ip);
        } catch (ResourceAllocationException ex) {
            s_logger.warn("Failed to allocate resource of type " + ex.getResourceType() + " for account " + owner);
            throw new AccountLimitException("Maximum number of public IP addresses for account: " + owner.getAccountName() + " has been exceeded.");
        }

        PublicIp ip = null;
        Transaction txn = Transaction.currentTxn();
        try {
            txn.start();

            owner = _accountDao.acquireInLockTable(ownerId);

            if (owner == null) {
                // this ownerId comes from owner or type Account. See the class "AccountVO" and the annotations in that class
                // to get the table name and field name that is queried to fill this ownerid.
                ConcurrentOperationException ex = new ConcurrentOperationException("Unable to lock account");
            }
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("lock account " + ownerId + " is acquired");
            }
            
            // If account has Account specific ip ranges, try to allocate ip from there
            Long vlanId = null;
            List<AccountVlanMapVO> maps = _accountVlanMapDao.listAccountVlanMapsByAccount(ownerId);
            if (maps != null && !maps.isEmpty()) {
                vlanId = maps.get(0).getVlanDbId();
            }


            ip = fetchNewPublicIp(dcId, null, vlanId, owner, VlanType.VirtualNetwork, guestNtwkId, 
                    isSourceNat, false, null, false, vpcId);
            IPAddressVO publicIp = ip.ip();

            markPublicIpAsAllocated(publicIp);
            _ipAddressDao.update(publicIp.getId(), publicIp);

            txn.commit();
            return ip;
        } finally {
            if (owner != null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Releasing lock account " + ownerId);
                }

                _accountDao.releaseFromLockTable(ownerId);
            }
            if (ip == null) {
                txn.rollback();
                s_logger.error("Unable to get source nat ip address for account " + ownerId);
            }
        }
    }

    /**
     * Returns the target account for an api command
     * 
     * @param accountName
     *            - non-null if the account name was passed in in the command
     * @param domainId
     *            - non-null if the domainId was passed in in the command.
     * @return
     */
    protected Account getAccountForApiCommand(String accountName, Long domainId) {
        Account account = UserContext.current().getCaller();

        if (_accountMgr.isAdmin(account.getType())) {
            // The admin is making the call, determine if it is for someone else or for himself
            if (domainId != null) {
                if ((account != null) && !_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                    // TBD: Check if call to addProxyObject() needs correction.
                    PermissionDeniedException ex = new PermissionDeniedException("Invalid domain id given, permission denied");
                    ex.addProxyObject("domain", domainId, "domainId");
                    throw ex;
                }
                if (accountName != null) {
                    Account userAccount = _accountMgr.getActiveAccountByName(accountName, domainId);
                    if (userAccount != null) {
                        account = userAccount;
                    } else {
                        // TBD: Check if call to addProxyObject() needs correction.
                        PermissionDeniedException ex = new PermissionDeniedException("Unable to find account " + accountName + " in specified domain, permission denied");
                        ex.addProxyObject("domain", domainId, "domainId");
                        throw ex;
                    }
                }
            } else {
                // the admin is calling the api on his own behalf
                return account;
            }
        }
        return account;
    }

    @Override
    public boolean applyIpAssociations(Network network, boolean continueOnError) throws ResourceUnavailableException {
        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        List<PublicIp> publicIps = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            for (IPAddressVO userIp : userIps) {
                PublicIp publicIp = new PublicIp(userIp, _vlanDao.findById(userIp.getVlanId()), 
                        NetUtils.createSequenceBasedMacAddress(userIp.getMacAddress()));
                publicIps.add(publicIp);
            }
        }

        boolean success = applyIpAssociations(network, false, continueOnError, publicIps);

        if (success) {
            for (IPAddressVO addr : userIps) {
                if (addr.getState() == IpAddress.State.Allocating) {
                    markPublicIpAsAllocated(addr);
                } else if (addr.getState() == IpAddress.State.Releasing) {
                    // Cleanup all the resources for ip address if there are any, and only then un-assign ip in the
                    // system
                    if (cleanupIpResources(addr.getId(), Account.ACCOUNT_ID_SYSTEM, _accountMgr.getSystemAccount())) {
                        s_logger.debug("Unassiging ip address " + addr);
                        _ipAddressDao.unassignIpAddress(addr.getId());  
                    } else {
                        success = false;
                        s_logger.warn("Failed to release resources for ip address id=" + addr.getId());
                    }
                }
            }
        }

        return success;
    }

    private Map<Provider, Set<Service>> getProviderServicesMap(long networkId) {
        Map<Provider, Set<Service>> map = new HashMap<Provider, Set<Service>>();
        List<NetworkServiceMapVO> nsms = _ntwkSrvcDao.getServicesInNetwork(networkId);
        for (NetworkServiceMapVO nsm : nsms) {
            Set<Service> services = map.get(Provider.getProvider(nsm.getProvider()));
            if (services == null) {
                services = new HashSet<Service>();
            }
            services.add(Service.getService(nsm.getService()));
            map.put(Provider.getProvider(nsm.getProvider()), services);
        }
        return map;
    }

    private Map<Service, Set<Provider>> getServiceProvidersMap(long networkId) {
        Map<Service, Set<Provider>> map = new HashMap<Service, Set<Provider>>();
        List<NetworkServiceMapVO> nsms = _ntwkSrvcDao.getServicesInNetwork(networkId);
        for (NetworkServiceMapVO nsm : nsms) {
            Set<Provider> providers = map.get(Service.getService(nsm.getService()));
            if (providers == null) {
                providers = new HashSet<Provider>();
            }
            providers.add(Provider.getProvider(nsm.getProvider()));
            map.put(Service.getService(nsm.getService()), providers);
        }
        return map;
    }

    /* Get a list of IPs, classify them by service */
    @Override
    public Map<PublicIp, Set<Service>> getIpToServices(List<PublicIp> publicIps, boolean rulesRevoked, boolean includingFirewall) {
        Map<PublicIp, Set<Service>> ipToServices = new HashMap<PublicIp, Set<Service>>();

        if (publicIps != null && !publicIps.isEmpty()) {
            Set<Long> networkSNAT = new HashSet<Long>();
            for (PublicIp ip : publicIps) {
                Set<Service> services = ipToServices.get(ip);
                if (services == null) {
                    services = new HashSet<Service>();
                }
                if (ip.isSourceNat()) {
                    if (!networkSNAT.contains(ip.getAssociatedWithNetworkId())) {
                        services.add(Service.SourceNat);
                        networkSNAT.add(ip.getAssociatedWithNetworkId());
                    } else {
                        CloudRuntimeException ex = new CloudRuntimeException("Multiple generic soure NAT IPs provided for network");
                        // see the IPAddressVO.java class.
                        ex.addProxyObject("user_ip_address", ip.getAssociatedWithNetworkId(), "networkId");
                        throw ex;
                    }
                }
                ipToServices.put(ip, services);

                // if IP in allocating state then it will not have any rules attached so skip IPAssoc to network service
                // provider
                if (ip.getState() == State.Allocating) {
                    continue;
                }

                // check if any active rules are applied on the public IP
                Set<Purpose> purposes = getPublicIpPurposeInRules(ip, false, includingFirewall);
                // Firewall rules didn't cover static NAT
                if (ip.isOneToOneNat() && ip.getAssociatedWithVmId() != null) {
                    if (purposes == null) {
                        purposes = new HashSet<Purpose>();
                    }
                    purposes.add(Purpose.StaticNat);
                }
                if (purposes == null || purposes.isEmpty()) {
                    // since no active rules are there check if any rules are applied on the public IP but are in
// revoking state
                    
                    purposes = getPublicIpPurposeInRules(ip, true, includingFirewall);
                    if (ip.isOneToOneNat()) {
                        if (purposes == null) {
                            purposes = new HashSet<Purpose>();
                        }
                        purposes.add(Purpose.StaticNat);
                    }
                    if (purposes == null || purposes.isEmpty()) {
                        // IP is not being used for any purpose so skip IPAssoc to network service provider
                        continue;
                    } else {
                        if (rulesRevoked) {
                            // no active rules/revoked rules are associated with this public IP, so remove the
// association with the provider
                            ip.setState(State.Releasing);
                        } else {
                            if (ip.getState() == State.Releasing) {
                                // rules are not revoked yet, so don't let the network service provider revoke the IP
// association
                                // mark IP is allocated so that IP association will not be removed from the provider
                                ip.setState(State.Allocated);
                            }
                        }
                    }
                }
                if (purposes.contains(Purpose.StaticNat)) {
                    services.add(Service.StaticNat);
                }
                if (purposes.contains(Purpose.LoadBalancing)) {
                    services.add(Service.Lb);
                }
                if (purposes.contains(Purpose.PortForwarding)) {
                    services.add(Service.PortForwarding);
                }
                if (purposes.contains(Purpose.Vpn)) {
                    services.add(Service.Vpn);
                }
                if (purposes.contains(Purpose.Firewall)) {
                    services.add(Service.Firewall);
                }
                if (services.isEmpty()) {
                    continue;
                }
                ipToServices.put(ip, services);
            }
        }
        return ipToServices;
    }

    public boolean canIpUsedForNonConserveService(PublicIp ip, Service service) {
        // If it's non-conserve mode, then the new ip should not be used by any other services
        List<PublicIp> ipList = new ArrayList<PublicIp>();
        ipList.add(ip);
        Map<PublicIp, Set<Service>> ipToServices = getIpToServices(ipList, false, false);
        Set<Service> services = ipToServices.get(ip);
        // Not used currently, safe
        if (services == null || services.isEmpty()) {
            return true;
        }
        // Since it's non-conserve mode, only one service should used for IP
        if (services.size() != 1) {
            throw new InvalidParameterException("There are multiple services used ip " + ip.getAddress() + ".");
        }
        if (service != null && !((Service) services.toArray()[0] == service || service.equals(Service.Firewall))) {
            throw new InvalidParameterException("The IP " + ip.getAddress() + " is already used as " + ((Service) services.toArray()[0]).getName() + " rather than " + service.getName());
        }
        return true;
    }

    protected boolean canIpsUsedForNonConserve(List<PublicIp> publicIps) {
        boolean result = true;
        for (PublicIp ip : publicIps) {
            result = canIpUsedForNonConserveService(ip, null);
            if (!result) {
                break;
            }
        }
        return result;
    }

    public boolean canIpsUseOffering(List<PublicIp> publicIps, long offeringId) {
        Map<PublicIp, Set<Service>> ipToServices = getIpToServices(publicIps, false, true);
        Map<Service, Set<Provider>> serviceToProviders = getNetworkOfferingServiceProvidersMap(offeringId);
        for (PublicIp ip : ipToServices.keySet()) {
            Set<Service> services = ipToServices.get(ip);
            Provider provider = null;
            for (Service service : services) {
                Set<Provider> curProviders = serviceToProviders.get(service);
                if (curProviders == null || curProviders.isEmpty()) {
                    continue;
                }
                Provider curProvider = (Provider) curProviders.toArray()[0];
                if (provider == null) {
                    provider = curProvider;
                    continue;
                }
                // We don't support multiple providers for one service now
                if (!provider.equals(curProvider)) {
                    throw new InvalidParameterException("There would be multiple providers for IP " + ip.getAddress() + " with the new network offering!");
                }
            }
        }
        return true;
    }

    public boolean canIpUsedForService(PublicIp publicIp, Service service, Long networkId) {
        List<PublicIp> ipList = new ArrayList<PublicIp>();
        ipList.add(publicIp);
        Map<PublicIp, Set<Service>> ipToServices = getIpToServices(ipList, false, true);
        Set<Service> services = ipToServices.get(publicIp);
        if (services == null || services.isEmpty()) {
            return true;
        }
        
        if (networkId == null) {
            networkId = publicIp.getAssociatedWithNetworkId();
        }
        
        // We only support one provider for one service now
        Map<Service, Set<Provider>> serviceToProviders = getServiceProvidersMap(networkId);
        Set<Provider> oldProviders = serviceToProviders.get(services.toArray()[0]);
        Provider oldProvider = (Provider) oldProviders.toArray()[0];
        // Since IP already has service to bind with, the oldProvider can't be null
        Set<Provider> newProviders = serviceToProviders.get(service);
        if (newProviders == null || newProviders.isEmpty()) {
            throw new InvalidParameterException("There is no new provider for IP " + publicIp.getAddress() + " of service " + service.getName() + "!");
        }
        Provider newProvider = (Provider) newProviders.toArray()[0];
        if (!oldProvider.equals(newProvider)) {
            throw new InvalidParameterException("There would be multiple providers for IP " + publicIp.getAddress() + "!");
        }
        return true;
    }

    /* Return a mapping between provider in the network and the IP they should applied */
    @Override
    public Map<Provider, ArrayList<PublicIp>> getProviderToIpList(Network network, Map<PublicIp, Set<Service>> ipToServices) {
        NetworkOffering offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
        if (!offering.isConserveMode()) {
            for (PublicIp ip : ipToServices.keySet()) {
                Set<Service> services = new HashSet<Service>() ;
                services.addAll(ipToServices.get(ip));
                if (services != null && services.contains(Service.Firewall)) {
                    services.remove(Service.Firewall);
                }
                if (services != null && services.size() > 1) {
                    throw new CloudRuntimeException("Ip " + ip.getAddress() + " is used by multiple services!");
                }
            }
        }
        Map<Service, Set<PublicIp>> serviceToIps = new HashMap<Service, Set<PublicIp>>();
        for (PublicIp ip : ipToServices.keySet()) {
            for (Service service : ipToServices.get(ip)) {
                Set<PublicIp> ips = serviceToIps.get(service);
                if (ips == null) {
                    ips = new HashSet<PublicIp>();
                }
                ips.add(ip);
                serviceToIps.put(service, ips);
            }
        }
        // TODO Check different provider for same IP
        Map<Provider, Set<Service>> providerToServices = getProviderServicesMap(network.getId());
        Map<Provider, ArrayList<PublicIp>> providerToIpList = new HashMap<Provider, ArrayList<PublicIp>>();
        for (Provider provider : providerToServices.keySet()) {
            Set<Service> services = providerToServices.get(provider);
            ArrayList<PublicIp> ipList = new ArrayList<PublicIp>();
            Set<PublicIp> ipSet = new HashSet<PublicIp>();
            for (Service service : services) {
                Set<PublicIp> serviceIps = serviceToIps.get(service);
                if (serviceIps == null || serviceIps.isEmpty()) {
                    continue;
                }
                ipSet.addAll(serviceIps);
            }
            Set<PublicIp> sourceNatIps = serviceToIps.get(Service.SourceNat);
            if (sourceNatIps != null && !sourceNatIps.isEmpty()) {
                ipList.addAll(0, sourceNatIps);
                ipSet.removeAll(sourceNatIps);
            }
            ipList.addAll(ipSet);
            providerToIpList.put(provider, ipList);
        }
        return providerToIpList;
    }

    protected boolean applyIpAssociations(Network network, boolean rulesRevoked, boolean continueOnError, 
            List<PublicIp> publicIps) throws ResourceUnavailableException {
        boolean success = true;

        Map<PublicIp, Set<Service>> ipToServices = getIpToServices(publicIps, rulesRevoked, true);
        Map<Provider, ArrayList<PublicIp>> providerToIpList = getProviderToIpList(network, ipToServices);

        for (Provider provider : providerToIpList.keySet()) {
            try {
                ArrayList<PublicIp> ips = providerToIpList.get(provider);
                if (ips == null || ips.isEmpty()) {
                    continue;
                }
                IpDeployer deployer = null;
                NetworkElement element = getElementImplementingProvider(provider.getName());
                if (element instanceof SourceNatServiceProvider) {
                    deployer = ((SourceNatServiceProvider) element).getIpDeployer(network);
                } else if (element instanceof StaticNatServiceProvider) {
                    deployer = ((StaticNatServiceProvider) element).getIpDeployer(network);
                } else if (element instanceof LoadBalancingServiceProvider) {
                    deployer = ((LoadBalancingServiceProvider) element).getIpDeployer(network);
                } else if (element instanceof PortForwardingServiceProvider) {
                    deployer = ((PortForwardingServiceProvider) element).getIpDeployer(network);
                } else if (element instanceof RemoteAccessVPNServiceProvider) {
                    deployer = ((RemoteAccessVPNServiceProvider) element).getIpDeployer(network);
                } else if (element instanceof ConnectivityProvider) {
                    // Nothing to do
                    s_logger.debug("ConnectivityProvider " + element.getClass().getSimpleName() + " has no ip associations");
                    continue;
                } else {
                    throw new CloudRuntimeException("Fail to get ip deployer for element: " + element);
                }
                Set<Service> services = new HashSet<Service>();
                for (PublicIp ip : ips) {
                    if (!ipToServices.containsKey(ip)) {
                        continue;
                    }
                    services.addAll(ipToServices.get(ip));
                }
                deployer.applyIps(network, ips, services);
            } catch (ResourceUnavailableException e) {
                success = false;
                if (!continueOnError) {
                    throw e;
                } else {
                    s_logger.debug("Resource is not available: " + provider.getName(), e);
                }
            }
        }

        return success;
    }

    Set<Purpose> getPublicIpPurposeInRules(PublicIp ip, boolean includeRevoked, boolean includingFirewall) {
        Set<Purpose> result = new HashSet<Purpose>();
        List<FirewallRuleVO> rules = null;
        if (includeRevoked) {
            rules = _firewallDao.listByIp(ip.getId());
        } else {
            rules = _firewallDao.listByIpAndNotRevoked(ip.getId());
        }

        if (rules == null || rules.isEmpty()) {
            return null;
        }

        for (FirewallRuleVO rule : rules) {
            if (rule.getPurpose() != Purpose.Firewall || includingFirewall) {
                result.add(rule.getPurpose());
            }
        }

        return result;
    }

    @Override
    public List<? extends Network> getIsolatedNetworksOwnedByAccountInZone(long zoneId, Account owner) {

        return _networksDao.listByZoneAndGuestType(owner.getId(), zoneId, Network.GuestType.Isolated, false);
    }
    
    @Override
    public List<? extends Network> getIsolatedNetworksWithSourceNATOwnedByAccountInZone(long zoneId, Account owner) {

        return _networksDao.listSourceNATEnabledNetworks(owner.getId(), zoneId, Network.GuestType.Isolated);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_ASSIGN, eventDescription = "allocating Ip", create = true)

    public IpAddress allocateIP(Account ipOwner, boolean isSystem, long zoneId) 
            throws ResourceAllocationException, InsufficientAddressCapacityException, ConcurrentOperationException {
        Account caller = UserContext.current().getCaller();
        long callerUserId = UserContext.current().getCallerUserId();
        // check permissions
        _accountMgr.checkAccess(caller, null, false, ipOwner);
        
        DataCenter zone = _configMgr.getZone(zoneId);
        
        return allocateIp(ipOwner, isSystem, caller, callerUserId, zone);
    }

    @DB
    public IpAddress allocateIp(Account ipOwner, boolean isSystem, Account caller, long callerUserId, DataCenter zone) 
            throws ConcurrentOperationException, ResourceAllocationException,
            InsufficientAddressCapacityException {
        
        VlanType vlanType = VlanType.VirtualNetwork;
        boolean assign = false;

        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getType())) {
            // zone is of type DataCenter. See DataCenterVO.java.
            PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation, " +
                    "Zone is currently disabled");
            ex.addProxyObject("data_center", zone.getId(), "zoneId");
            throw ex;
        }

        PublicIp ip = null;

        Transaction txn = Transaction.currentTxn();
        Account accountToLock = null;
        try {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Associate IP address called by the user " + callerUserId + " account " + ipOwner.getId());
            }
            accountToLock = _accountDao.acquireInLockTable(ipOwner.getId());
            if (accountToLock == null) {
                s_logger.warn("Unable to lock account: " + ipOwner.getId());
                throw new ConcurrentOperationException("Unable to acquire account lock");
            }

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Associate IP address lock acquired");
            }

            // Check that the maximum number of public IPs for the given
            // accountId will not be exceeded
            _resourceLimitMgr.checkResourceLimit(accountToLock, ResourceType.public_ip);

            txn.start();

            ip = fetchNewPublicIp(zone.getId(), null, null, ipOwner, vlanType, null, 
                    false, assign, null, isSystem, null);

            if (ip == null) {

                InsufficientAddressCapacityException ex = new InsufficientAddressCapacityException
                        ("Unable to find available public IP addresses", DataCenter.class, zone.getId());
                ex.addProxyObject("data_center", zone.getId(), "zoneId");
                throw ex;
            }
            UserContext.current().setEventDetails("Ip Id: " + ip.getId());
            Ip ipAddress = ip.getAddress();

            s_logger.debug("Got " + ipAddress + " to assign for account " + ipOwner.getId() + " in zone " + zone.getId());

            txn.commit();
        } finally {
            if (accountToLock != null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Releasing lock account " + ipOwner);
                }
                _accountDao.releaseFromLockTable(ipOwner.getId());
                s_logger.debug("Associate IP address lock released");
            }
        }
        return ip;
    }

    
    protected IPAddressVO getExistingSourceNatInNetwork(long ownerId, Long networkId) {

        List<IPAddressVO> addrs = listPublicIpsAssignedToGuestNtwk(ownerId, networkId, true);

        IPAddressVO sourceNatIp = null;
        if (addrs.isEmpty()) {
            return null;
        } else {
            // Account already has ip addresses
            for (IPAddressVO addr : addrs) {
                if (addr.isSourceNat()) {
                    sourceNatIp = addr;
                    return sourceNatIp;
                }
            }

            assert (sourceNatIp != null) : "How do we get a bunch of ip addresses but none of them are source nat? " +
                    "account=" + ownerId + "; networkId=" + networkId;
        } 
        
        return sourceNatIp;
    }

    @DB
    @Override
    public IPAddressVO associateIPToGuestNetwork(long ipId, long networkId, boolean releaseOnFailure) 
            throws ResourceAllocationException, ResourceUnavailableException, 
    InsufficientAddressCapacityException, ConcurrentOperationException {
        Account caller = UserContext.current().getCaller();
        Account owner = null;

        IPAddressVO ipToAssoc = _ipAddressDao.findById(ipId);
        if (ipToAssoc != null) {
            _accountMgr.checkAccess(caller, null, true, ipToAssoc);
            owner = _accountMgr.getAccount(ipToAssoc.getAllocatedToAccountId());
        } else {
            s_logger.debug("Unable to find ip address by id: " + ipId);
            return null;
        }
        
        if (ipToAssoc.getAssociatedWithNetworkId() != null) {
            s_logger.debug("IP " + ipToAssoc + " is already assocaited with network id" + networkId);
            return ipToAssoc;
        }
        
        Network network = _networksDao.findById(networkId);
        if (network != null) {
            _accountMgr.checkAccess(owner, AccessType.UseNetwork, false, network);
        } else {
            s_logger.debug("Unable to find ip address by id: " + ipId);
            return null;
        }
        
        DataCenter zone = _configMgr.getZone(network.getDataCenterId());

        // allow associating IP addresses to guest network only
        if (network.getTrafficType() != TrafficType.Guest) {
            throw new InvalidParameterValueException("Ip address can be associated to the network with trafficType " + 
                                                    TrafficType.Guest);
        }

        // Check that network belongs to IP owner - skip this check for Basic zone as there is just one guest network,
        // and it belongs to the system
        if (zone.getNetworkType() != NetworkType.Basic && network.getAccountId() != owner.getId()) {
            throw new InvalidParameterValueException("The owner of the network is not the same as owner of the IP");
        }
        
        // In Advance zone only allow to do IP assoc for Isolated networks with source nat service enabled
        if (zone.getNetworkType() == NetworkType.Advanced && 
                !(network.getGuestType() == GuestType.Isolated && areServicesSupportedInNetwork(network.getId(), 
                        Service.SourceNat))) {
            throw new InvalidParameterValueException("In zone of type " + NetworkType.Advanced + 
                    " ip address can be associated only to the network of guest type " + GuestType.Isolated + " with the "
                    + Service.SourceNat.getName() + " enabled");
        }
        
        NetworkOffering offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
        boolean sharedSourceNat = offering.getSharedSourceNat();
        boolean isSourceNat = false;
        if (!sharedSourceNat) {
            if (getExistingSourceNatInNetwork(owner.getId(), networkId) == null) {
                if (network.getGuestType() == GuestType.Isolated && network.getVpcId() == null) {
                    isSourceNat = true;
                }
            }
        }
        
        s_logger.debug("Associating ip " + ipToAssoc + " to network " + network);
        
        IPAddressVO ip = _ipAddressDao.findById(ipId);
        //update ip address with networkId
        ip.setAssociatedWithNetworkId(networkId);
        ip.setSourceNat(isSourceNat);
        _ipAddressDao.update(ipId, ip);
        
        boolean success = false;
        try {
            success = applyIpAssociations(network, false);
            if (success) {
                s_logger.debug("Successfully associated ip address " + ip.getAddress().addr() + " to network " + network);
            } else {
                s_logger.warn("Failed to associate ip address " + ip.getAddress().addr() + " to network " + network);
            }
            return ip;
        } finally {
            if (!success && releaseOnFailure) {
                if (ip != null) {
                    try {
                        s_logger.warn("Failed to associate ip address, so releasing ip from the database " + ip);
                        _ipAddressDao.markAsUnavailable(ip.getId());
                        if (!applyIpAssociations(network, true)) {
                            // if fail to apply ip assciations again, unassign ip address without updating resource
                            // count and generating usage event as there is no need to keep it in the db
                            _ipAddressDao.unassignIpAddress(ip.getId());
                        }
                    } catch (Exception e) {
                        s_logger.warn("Unable to disassociate ip address for recovery", e);
                    }
                }
            }
        }
    }
    

    @Override
    @DB
    public boolean disassociatePublicIpAddress(long addrId, long userId, Account caller) {

        boolean success = true;
        // Cleanup all ip address resources - PF/LB/Static nat rules
        if (!cleanupIpResources(addrId, userId, caller)) {
            success = false;
            s_logger.warn("Failed to release resources for ip address id=" + addrId);
        }

        IPAddressVO ip = markIpAsUnavailable(addrId);

        assert (ip != null) : "Unable to mark the ip address id=" + addrId + " as unavailable.";
        if (ip == null) {
            return true;
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing ip id=" + addrId + "; sourceNat = " + ip.isSourceNat());
        }

        if (ip.getAssociatedWithNetworkId() != null) {
            Network network = _networksDao.findById(ip.getAssociatedWithNetworkId());
            try {
                if (!applyIpAssociations(network, true)) {
                    s_logger.warn("Unable to apply ip address associations for " + network);
                    success = false;
                }
            } catch (ResourceUnavailableException e) {
                throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
            }
        } else {
            if (ip.getState() == IpAddress.State.Releasing) {
                _ipAddressDao.unassignIpAddress(ip.getId());
            }
        }

        if (success) {
            s_logger.debug("Released a public ip id=" + addrId);
        }

        return success;
    }

    @Override
    @DB
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _name = name;

        _configs = _configDao.getConfiguration("AgentManager", params);
        _networkGcWait = NumbersUtil.parseInt(_configs.get(Config.NetworkGcWait.key()), 600);
        _networkGcInterval = NumbersUtil.parseInt(_configs.get(Config.NetworkGcInterval.key()), 600);

        _configs = _configDao.getConfiguration("Network", params);
        _networkDomain = _configs.get(Config.GuestDomainSuffix.key());

        _cidrLimit = NumbersUtil.parseInt(_configs.get(Config.NetworkGuestCidrLimit.key()), 22);
        _networkLockTimeout = NumbersUtil.parseInt(_configs.get(Config.NetworkLockTimeout.key()), 600);

        NetworkOfferingVO publicNetworkOffering = new NetworkOfferingVO(NetworkOfferingVO.SystemPublicNetwork, TrafficType.Public, true);
        publicNetworkOffering = _networkOfferingDao.persistDefaultNetworkOffering(publicNetworkOffering);
        _systemNetworks.put(NetworkOfferingVO.SystemPublicNetwork, publicNetworkOffering);
        NetworkOfferingVO managementNetworkOffering = new NetworkOfferingVO(NetworkOfferingVO.SystemManagementNetwork, TrafficType.Management, false);
        managementNetworkOffering = _networkOfferingDao.persistDefaultNetworkOffering(managementNetworkOffering);
        _systemNetworks.put(NetworkOfferingVO.SystemManagementNetwork, managementNetworkOffering);
        NetworkOfferingVO controlNetworkOffering = new NetworkOfferingVO(NetworkOfferingVO.SystemControlNetwork, TrafficType.Control, false);
        controlNetworkOffering = _networkOfferingDao.persistDefaultNetworkOffering(controlNetworkOffering);
        _systemNetworks.put(NetworkOfferingVO.SystemControlNetwork, controlNetworkOffering);
        NetworkOfferingVO storageNetworkOffering = new NetworkOfferingVO(NetworkOfferingVO.SystemStorageNetwork, TrafficType.Storage, true);
        storageNetworkOffering = _networkOfferingDao.persistDefaultNetworkOffering(storageNetworkOffering);
        _systemNetworks.put(NetworkOfferingVO.SystemStorageNetwork, storageNetworkOffering);
        NetworkOfferingVO privateGatewayNetworkOffering = new NetworkOfferingVO(NetworkOfferingVO.SystemPrivateGatewayNetworkOffering,
                GuestType.Isolated);
        privateGatewayNetworkOffering = _networkOfferingDao.persistDefaultNetworkOffering(privateGatewayNetworkOffering);
        _systemNetworks.put(NetworkOfferingVO.SystemPrivateGatewayNetworkOffering, privateGatewayNetworkOffering);
        _privateOfferingId = privateGatewayNetworkOffering.getId();


        // populate providers
        Map<Network.Service, Set<Network.Provider>> defaultSharedNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        Set<Network.Provider> defaultProviders = new HashSet<Network.Provider>();

        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultSharedNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.UserData, defaultProviders);

        Map<Network.Service, Set<Network.Provider>> defaultIsolatedNetworkOfferingProviders = defaultSharedNetworkOfferingProviders;

        Map<Network.Service, Set<Network.Provider>> defaultSharedSGEnabledNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        Set<Provider> sgProviders = new HashSet<Provider>();
        sgProviders.add(Provider.SecurityGroupProvider);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.SecurityGroup, sgProviders);

        Map<Network.Service, Set<Network.Provider>> defaultIsolatedSourceNatEnabledNetworkOfferingProviders = 
                new HashMap<Network.Service, Set<Network.Provider>>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Firewall, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Gateway, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Lb, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.SourceNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.StaticNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.PortForwarding, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Vpn, defaultProviders);
        
        
        Map<Network.Service, Set<Network.Provider>> defaultVPCOffProviders = 
                new HashMap<Network.Service, Set<Network.Provider>>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultVPCOffProviders.put(Service.Dhcp, defaultProviders);
        defaultVPCOffProviders.put(Service.Dns, defaultProviders);
        defaultVPCOffProviders.put(Service.UserData, defaultProviders);
        defaultVPCOffProviders.put(Service.NetworkACL, defaultProviders);
        defaultVPCOffProviders.put(Service.Gateway, defaultProviders);
        defaultVPCOffProviders.put(Service.Lb, defaultProviders);
        defaultVPCOffProviders.put(Service.SourceNat, defaultProviders);
        defaultVPCOffProviders.put(Service.StaticNat, defaultProviders);
        defaultVPCOffProviders.put(Service.PortForwarding, defaultProviders);
        defaultVPCOffProviders.put(Service.Vpn, defaultProviders);

        Transaction txn = Transaction.currentTxn();
        txn.start();
        // diff between offering #1 and #2 - securityGroup is enabled for the first, and disabled for the third

        NetworkOfferingVO offering = null;
        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedNetworkOfferingWithSGService) == null) {
            offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultSharedNetworkOfferingWithSGService, "Offering for Shared Security group enabled networks", TrafficType.Guest, null,
                    true, Availability.Optional, null, defaultSharedNetworkOfferingProviders, true, Network.GuestType.Shared, false, null, true, null, true);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        }

        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedNetworkOffering) == null) {
            offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultSharedNetworkOffering, "Offering for Shared networks", TrafficType.Guest, null, true, Availability.Optional, null,
                    defaultSharedNetworkOfferingProviders, true, Network.GuestType.Shared, false, null, true, null, true);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        }

        Map<Network.Service, Set<Network.Provider>> defaultINetworkOfferingProvidersForVpcNetwork = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VPCVirtualRouter);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.Dhcp, defaultProviders);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.Dns, defaultProviders);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.UserData, defaultProviders);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.Firewall, defaultProviders);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.Gateway, defaultProviders);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.Lb, defaultProviders);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.SourceNat, defaultProviders);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.StaticNat, defaultProviders);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.PortForwarding, defaultProviders);
        defaultINetworkOfferingProvidersForVpcNetwork.put(Service.Vpn, defaultProviders);

        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingWithSourceNatService) == null) {
            offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingWithSourceNatService,
                    "Offering for Isolated networks with Source Nat service enabled", TrafficType.Guest,
                    null, false, Availability.Required, null, defaultINetworkOfferingProvidersForVpcNetwork,
                    true, Network.GuestType.Isolated, false, null, true, null, false);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        }
        
        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworks) == null) {
            offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworks,
                    "Offering for Isolated VPC networks with Source Nat service enabled", TrafficType.Guest,
                    null, false, Availability.Optional, null, defaultVPCOffProviders,
                    true, Network.GuestType.Isolated, false, null, false, null, false);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        }
        
        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksNoLB) == null) {
            //remove LB service
            defaultVPCOffProviders.remove(Service.Lb);
            offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksNoLB,
                    "Offering for Isolated VPC networks with Source Nat service enabled and LB service disabled", TrafficType.Guest,
                    null, false, Availability.Optional, null, defaultVPCOffProviders,
                    true, Network.GuestType.Isolated, false, null, false, null, false);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        }

        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOffering) == null) {
            offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOffering, 
                    "Offering for Isolated networks with no Source Nat service", TrafficType.Guest, null, true,
                    Availability.Optional, null, defaultIsolatedNetworkOfferingProviders, true, Network.GuestType.Isolated,
                    false, null, true, null, true);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        }
        
        Map<Network.Service, Set<Network.Provider>> netscalerServiceProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        Set<Network.Provider> vrProvider = new HashSet<Network.Provider>();
        vrProvider.add(Provider.VirtualRouter);
        Set<Network.Provider> sgProvider = new HashSet<Network.Provider>();
        sgProvider.add(Provider.SecurityGroupProvider);
        Set<Network.Provider> nsProvider = new HashSet<Network.Provider>();
        nsProvider.add(Provider.Netscaler);
        netscalerServiceProviders.put(Service.Dhcp, vrProvider);
        netscalerServiceProviders.put(Service.Dns, vrProvider);
        netscalerServiceProviders.put(Service.UserData, vrProvider);
        netscalerServiceProviders.put(Service.SecurityGroup, sgProvider);
        netscalerServiceProviders.put(Service.StaticNat, nsProvider);
        netscalerServiceProviders.put(Service.Lb, nsProvider);
        
        Map<Service, Map<Capability, String>> serviceCapabilityMap = new HashMap<Service, Map<Capability, String>>();
        Map<Capability, String> elb = new HashMap<Capability, String>();
        elb.put(Capability.ElasticLb, "true");
        Map<Capability, String> eip = new HashMap<Capability, String>();
        eip.put(Capability.ElasticIp, "true");
        serviceCapabilityMap.put(Service.Lb, elb);
        serviceCapabilityMap.put(Service.StaticNat, eip);
        
        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedEIPandELBNetworkOffering) == null) {
            offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultSharedEIPandELBNetworkOffering, "Offering for Shared networks with Elastic IP and Elastic LB capabilities", TrafficType.Guest, null, true,
                    Availability.Optional, null, netscalerServiceProviders, true, Network.GuestType.Shared, false, null, true, serviceCapabilityMap, true);
            offering.setState(NetworkOffering.State.Enabled);
            offering.setDedicatedLB(false);
            _networkOfferingDao.update(offering.getId(), offering);
        }

        txn.commit();

        AccountsUsingNetworkSearch = _accountDao.createSearchBuilder();
        SearchBuilder<NetworkAccountVO> networkAccountSearch = _networksDao.createSearchBuilderForAccount();
        AccountsUsingNetworkSearch.join("nc", networkAccountSearch, AccountsUsingNetworkSearch.entity().getId(), networkAccountSearch.entity().getAccountId(), JoinType.INNER);
        networkAccountSearch.and("config", networkAccountSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        networkAccountSearch.and("owner", networkAccountSearch.entity().isOwner(), SearchCriteria.Op.EQ);
        AccountsUsingNetworkSearch.done();

        AssignIpAddressSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressSearch.and("dc", AssignIpAddressSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressSearch.and("allocated", AssignIpAddressSearch.entity().getAllocatedTime(), Op.NULL);
        AssignIpAddressSearch.and("vlanId", AssignIpAddressSearch.entity().getVlanId(), Op.EQ);
        SearchBuilder<VlanVO> vlanSearch = _vlanDao.createSearchBuilder();
        vlanSearch.and("type", vlanSearch.entity().getVlanType(), Op.EQ);
        vlanSearch.and("networkId", vlanSearch.entity().getNetworkId(), Op.EQ);
        AssignIpAddressSearch.join("vlan", vlanSearch, vlanSearch.entity().getId(), AssignIpAddressSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressSearch.done();

        AssignIpAddressFromPodVlanSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressFromPodVlanSearch.and("dc", AssignIpAddressFromPodVlanSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.and("allocated", AssignIpAddressFromPodVlanSearch.entity().getAllocatedTime(), Op.NULL);
        SearchBuilder<VlanVO> podVlanSearch = _vlanDao.createSearchBuilder();
        podVlanSearch.and("type", podVlanSearch.entity().getVlanType(), Op.EQ);
        podVlanSearch.and("networkId", podVlanSearch.entity().getNetworkId(), Op.EQ);
        SearchBuilder<PodVlanMapVO> podVlanMapSB = _podVlanMapDao.createSearchBuilder();
        podVlanMapSB.and("podId", podVlanMapSB.entity().getPodId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.join("podVlanMapSB", podVlanMapSB, podVlanMapSB.entity().getVlanDbId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressFromPodVlanSearch.join("vlan", podVlanSearch, podVlanSearch.entity().getId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressFromPodVlanSearch.done();

        IpAddressSearch = _ipAddressDao.createSearchBuilder();
        IpAddressSearch.and("accountId", IpAddressSearch.entity().getAllocatedToAccountId(), Op.EQ);
        IpAddressSearch.and("dataCenterId", IpAddressSearch.entity().getDataCenterId(), Op.EQ);
        IpAddressSearch.and("vpcId", IpAddressSearch.entity().getVpcId(), Op.EQ);
        IpAddressSearch.and("associatedWithNetworkId", IpAddressSearch.entity().getAssociatedWithNetworkId(), Op.EQ);
        SearchBuilder<VlanVO> virtualNetworkVlanSB = _vlanDao.createSearchBuilder();
        virtualNetworkVlanSB.and("vlanType", virtualNetworkVlanSB.entity().getVlanType(), Op.EQ);
        IpAddressSearch.join("virtualNetworkVlanSB", virtualNetworkVlanSB, IpAddressSearch.entity().getVlanId(), virtualNetworkVlanSB.entity().getId(), JoinBuilder.JoinType.INNER);
        IpAddressSearch.done();

        NicForTrafficTypeSearch = _nicDao.createSearchBuilder();
        SearchBuilder<NetworkVO> networkSearch = _networksDao.createSearchBuilder();
        NicForTrafficTypeSearch.join("network", networkSearch, networkSearch.entity().getId(), NicForTrafficTypeSearch.entity().getNetworkId(), JoinType.INNER);
        NicForTrafficTypeSearch.and("instance", NicForTrafficTypeSearch.entity().getInstanceId(), Op.EQ);
        networkSearch.and("traffictype", networkSearch.entity().getTrafficType(), Op.EQ);
        NicForTrafficTypeSearch.done();

        _executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Network-Scavenger"));

        _allowSubdomainNetworkAccess = Boolean.valueOf(_configs.get(Config.SubDomainNetworkAccess.key()));

        _agentMgr.registerForHostEvents(this, true, false, true);

        s_logger.info("Network Manager is configured.");

        return true;
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    public boolean start() {

        // populate s_serviceToImplementedProvidersMap & s_providerToNetworkElementMap with current _networkElements
        // Need to do this in start() since _networkElements are not completely configured until then.
        for (NetworkElement element : _networkElements) {
            Map<Service, Map<Capability, String>> capabilities = element.getCapabilities();
            Provider implementedProvider = element.getProvider();
            if (implementedProvider != null) {
                if (s_providerToNetworkElementMap.containsKey(implementedProvider.getName())) {
                    s_logger.error("Cannot start NetworkManager: Provider <-> NetworkElement must be a one-to-one map, " +
                            "multiple NetworkElements found for Provider: " + implementedProvider.getName());
                    return false;
                }
                s_providerToNetworkElementMap.put(implementedProvider.getName(), element.getName());
            }
            if (capabilities != null && implementedProvider != null) {
                for (Service service : capabilities.keySet()) {
                    if (s_serviceToImplementedProvidersMap.containsKey(service)) {
                        List<Provider> providers = s_serviceToImplementedProvidersMap.get(service);
                        providers.add(implementedProvider);
                    } else {
                        List<Provider> providers = new ArrayList<Provider>();
                        providers.add(implementedProvider);
                        s_serviceToImplementedProvidersMap.put(service, providers);
                    }
                }
            }
        }

        _executor.scheduleWithFixedDelay(new NetworkGarbageCollector(), _networkGcInterval, _networkGcInterval, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    protected NetworkManagerImpl() {
    }

    @Override
    public List<IPAddressVO> listPublicIpsAssignedToGuestNtwk(long accountId, long associatedNetworkId, Boolean sourceNat) {
        SearchCriteria<IPAddressVO> sc = IpAddressSearch.create();
        sc.setParameters("accountId", accountId);
        sc.setParameters("associatedWithNetworkId", associatedNetworkId); 

        if (sourceNat != null) {
            sc.addAnd("sourceNat", SearchCriteria.Op.EQ, sourceNat);
        }
        sc.setJoinParameters("virtualNetworkVlanSB", "vlanType", VlanType.VirtualNetwork);

        return _ipAddressDao.search(sc, null);
    }
    
    @Override
    public List<IPAddressVO> listPublicIpsAssignedToAccount(long accountId, long dcId, Boolean sourceNat) {
        SearchCriteria<IPAddressVO> sc = IpAddressSearch.create();
        sc.setParameters("accountId", accountId);
        sc.setParameters("dataCenterId", dcId);

        if (sourceNat != null) {
            sc.addAnd("sourceNat", SearchCriteria.Op.EQ, sourceNat);
        }
        sc.setJoinParameters("virtualNetworkVlanSB", "vlanType", VlanType.VirtualNetwork);

        return _ipAddressDao.search(sc, null);
    }


    @Override
    public List<NetworkVO> setupNetwork(Account owner, NetworkOfferingVO offering, DeploymentPlan plan, String name, 
            String displayText, boolean isDefault)
            throws ConcurrentOperationException {
        return setupNetwork(owner, offering, null, plan, name, displayText, false, null, null, null, null);
    }

    @Override
    @DB
    public List<NetworkVO> setupNetwork(Account owner, NetworkOfferingVO offering, Network predefined, DeploymentPlan 
            plan, String name, String displayText, boolean errorIfAlreadySetup, Long domainId,
            ACLType aclType, Boolean subdomainAccess, Long vpcId) throws ConcurrentOperationException {
        
        Account locked = _accountDao.acquireInLockTable(owner.getId());
        if (locked == null) {
            throw new ConcurrentOperationException("Unable to acquire lock on " + owner);
        }

        try {
            if (predefined == null
                    || (offering.getTrafficType() != TrafficType.Guest && predefined.getCidr() == null && predefined.getBroadcastUri() == null && 
                    !(predefined.getBroadcastDomainType() == BroadcastDomainType.Vlan || predefined.getBroadcastDomainType() == BroadcastDomainType.Lswitch))) {
                List<NetworkVO> configs = _networksDao.listBy(owner.getId(), offering.getId(), plan.getDataCenterId());
                if (configs.size() > 0) {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Found existing network configuration for offering " + offering + ": " + configs.get(0));
                    }

                    if (errorIfAlreadySetup) {
                        InvalidParameterValueException ex = new InvalidParameterValueException("Found existing network configuration (with specified id) for offering (with specified id)");
                        ex.addProxyObject(offering, offering.getId(), "offeringId");
                        ex.addProxyObject(configs.get(0), configs.get(0).getId(), "networkConfigId");                       
                        throw ex;
                    } else {
                        return configs;
                    }
                }
            } else if (predefined != null && predefined.getCidr() != null && predefined.getBroadcastUri() == null && vpcId == null) {
                // don't allow to have 2 networks with the same cidr in the same zone for the account
                List<NetworkVO> configs = _networksDao.listBy(owner.getId(), plan.getDataCenterId(), predefined.getCidr(), true);
                if (configs.size() > 0) {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Found existing network configuration for offering " + offering + ": " + configs.get(0));
                    }

                    if (errorIfAlreadySetup) {
                        InvalidParameterValueException ex = new InvalidParameterValueException("Found existing network configuration (with specified id) for offering (with specified id)");
                        ex.addProxyObject(offering, offering.getId(), "offeringId");
                        ex.addProxyObject(configs.get(0), configs.get(0).getId(), "networkConfigId");                       
                        throw ex;
                    } else {
                        return configs;
                    }
                }
            }

            List<NetworkVO> networks = new ArrayList<NetworkVO>();

            long related = -1;

            for (NetworkGuru guru : _networkGurus) {
                Network network = guru.design(offering, plan, predefined, owner);
                if (network == null) {
                    continue;
                }

                if (network.getId() != -1) {
                    if (network instanceof NetworkVO) {
                        networks.add((NetworkVO) network);
                    } else {
                        networks.add(_networksDao.findById(network.getId()));
                    }
                    continue;
                }

                long id = _networksDao.getNextInSequence(Long.class, "id");
                if (related == -1) {
                    related = id;
                }

                Transaction txn = Transaction.currentTxn();
                txn.start();

                NetworkVO vo = new NetworkVO(id, network, offering.getId(), guru.getName(), owner.getDomainId(), owner.getId(),
                        related, name, displayText, predefined.getNetworkDomain(), offering.getGuestType(), 
                        plan.getDataCenterId(), plan.getPhysicalNetworkId(), aclType, offering.getSpecifyIpRanges(), vpcId);
                networks.add(_networksDao.persist(vo, vo.getGuestType() == Network.GuestType.Isolated, 
                        finalizeServicesAndProvidersForNetwork(offering, plan.getPhysicalNetworkId())));

                if (domainId != null && aclType == ACLType.Domain) {
                    _networksDao.addDomainToNetwork(id, domainId, subdomainAccess);
                }

                txn.commit();
            }

            if (networks.size() < 1) {
                // see networkOfferingVO.java
                CloudRuntimeException ex = new CloudRuntimeException("Unable to convert network offering with specified id to network profile");
                ex.addProxyObject(offering, offering.getId(), "offeringId");
                throw ex;
            }

            return networks;
        } finally {
            s_logger.debug("Releasing lock for " + locked);
            _accountDao.releaseFromLockTable(locked.getId());
        }
    }

    @Override
    public List<NetworkOfferingVO> getSystemAccountNetworkOfferings(String... offeringNames) {
        List<NetworkOfferingVO> offerings = new ArrayList<NetworkOfferingVO>(offeringNames.length);
        for (String offeringName : offeringNames) {
            NetworkOfferingVO network = _systemNetworks.get(offeringName);
            if (network == null) {
                throw new CloudRuntimeException("Unable to find system network profile for " + offeringName);
            }
            offerings.add(network);
        }
        return offerings;
    }

    @Override
    @DB
    public void allocate(VirtualMachineProfile<? extends VMInstanceVO> vm, List<Pair<NetworkVO, NicProfile>> networks) 
            throws InsufficientCapacityException, ConcurrentOperationException {
        Transaction txn = Transaction.currentTxn();
        txn.start();

        int deviceId = 0;

        boolean[] deviceIds = new boolean[networks.size()];
        Arrays.fill(deviceIds, false);

        List<NicProfile> nics = new ArrayList<NicProfile>(networks.size());
        NicProfile defaultNic = null;

        for (Pair<NetworkVO, NicProfile> network : networks) {
            NetworkVO config = network.first();
            NicProfile requested = network.second();
            
            Boolean isDefaultNic = false;
            if (vm != null && (requested != null && requested.isDefaultNic())) {
                isDefaultNic = true;
            }
            
            while (deviceIds[deviceId] && deviceId < deviceIds.length) {
                deviceId++;
            }
            
            Pair<NicProfile,Integer> vmNicPair = allocateNic(requested, config, isDefaultNic, 
                    deviceId, vm);
            
            NicProfile vmNic = vmNicPair.first();
            if (vmNic == null) {
                continue;
            }
            
            deviceId = vmNicPair.second();
            
            int devId = vmNic.getDeviceId();
            if (devId > deviceIds.length) {
                throw new IllegalArgumentException("Device id for nic is too large: " + vmNic);
            }
            if (deviceIds[devId]) {
                throw new IllegalArgumentException("Conflicting device id for two different nics: " + vmNic);
            }

            deviceIds[devId] = true;
            
            if (vmNic.isDefaultNic()) {
                if (defaultNic != null) {
                    throw new IllegalArgumentException("You cannot specify two nics as default nics: nic 1 = " + 
                defaultNic + "; nic 2 = " + vmNic);
                }
                defaultNic = vmNic;
            }
            
            nics.add(vmNic);
            vm.addNic(vmNic);
            
        }

        if (nics.size() != networks.size()) {
            s_logger.warn("Number of nics " + nics.size() + " doesn't match number of requested networks " + networks.size());
            throw new CloudRuntimeException("Number of nics " + nics.size() + " doesn't match number of requested networks " + networks.size());
        }

        if (nics.size() == 1) {
            nics.get(0).setDefaultNic(true);
        }

        txn.commit();
    }
    
    
    @DB
    @Override
    public Pair<NicProfile,Integer> allocateNic(NicProfile requested, Network network, Boolean isDefaultNic, 
            int deviceId, VirtualMachineProfile<? extends VMInstanceVO> vm) throws InsufficientVirtualNetworkCapcityException,
            InsufficientAddressCapacityException, ConcurrentOperationException{
        
        NetworkVO ntwkVO = _networksDao.findById(network.getId());
        s_logger.debug("Allocating nic for vm " + vm.getVirtualMachine() + " in network " + network + " with requested profile " + requested);
        NetworkGuru guru = _networkGurus.get(ntwkVO.getGuruName());

        if (requested != null && requested.getMode() == null) {
            requested.setMode(network.getMode());
        }
        NicProfile profile = guru.allocate(network, requested, vm);
        if (isDefaultNic != null) {
            profile.setDefaultNic(isDefaultNic);
        }

        if (profile == null) {
            return null;
        }

        if (requested != null && requested.getMode() == null) {
            profile.setMode(requested.getMode());
        } else {
            profile.setMode(network.getMode());
        }

        NicVO vo = new NicVO(guru.getName(), vm.getId(), network.getId(), vm.getType());

        deviceId = applyProfileToNic(vo, profile, deviceId);

        vo = _nicDao.persist(vo);
    
        Integer networkRate = getNetworkRate(network.getId(), vm.getId());
        NicProfile vmNic = new NicProfile(vo, network, vo.getBroadcastUri(), vo.getIsolationUri(), networkRate, 
                isSecurityGroupSupportedInNetwork(network), getNetworkTag(vm.getHypervisorType(),
                network));
        
        return new Pair<NicProfile,Integer>(vmNic, Integer.valueOf(deviceId));
    }    

    protected Integer applyProfileToNic(NicVO vo, NicProfile profile, Integer deviceId) {
        if (profile.getDeviceId() != null) {
            vo.setDeviceId(profile.getDeviceId());
        } else if (deviceId != null) {
            vo.setDeviceId(deviceId++);
        }

        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }

        vo.setDefaultNic(profile.isDefaultNic());

        if (profile.getIp4Address() != null) {
            vo.setIp4Address(profile.getIp4Address());
            vo.setAddressFormat(AddressFormat.Ip4);
        }

        if (profile.getMacAddress() != null) {
            vo.setMacAddress(profile.getMacAddress());
        }

        vo.setMode(profile.getMode());
        vo.setNetmask(profile.getNetmask());
        vo.setGateway(profile.getGateway());

        if (profile.getBroadCastUri() != null) {
            vo.setBroadcastUri(profile.getBroadCastUri());
        }

        if (profile.getIsolationUri() != null) {
            vo.setIsolationUri(profile.getIsolationUri());
        }

        vo.setState(Nic.State.Allocated);
        return deviceId;
    }

    protected void applyProfileToNicForRelease(NicVO vo, NicProfile profile) {
        vo.setGateway(profile.getGateway());
        vo.setAddressFormat(profile.getFormat());
        vo.setIp4Address(profile.getIp4Address());
        vo.setIp6Address(profile.getIp6Address());
        vo.setMacAddress(profile.getMacAddress());
        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }
        vo.setBroadcastUri(profile.getBroadCastUri());
        vo.setIsolationUri(profile.getIsolationUri());
        vo.setNetmask(profile.getNetmask());
    }

    protected void applyProfileToNetwork(NetworkVO network, NetworkProfile profile) {
        network.setBroadcastUri(profile.getBroadcastUri());
        network.setDns1(profile.getDns1());
        network.setDns2(profile.getDns2());
        network.setPhysicalNetworkId(profile.getPhysicalNetworkId());
    }

    protected NicTO toNicTO(NicVO nic, NicProfile profile, NetworkVO config) {
        NicTO to = new NicTO();
        to.setDeviceId(nic.getDeviceId());
        to.setBroadcastType(config.getBroadcastDomainType());
        to.setType(config.getTrafficType());
        to.setIp(nic.getIp4Address());
        to.setNetmask(nic.getNetmask());
        to.setMac(nic.getMacAddress());
        to.setDns1(profile.getDns1());
        to.setDns2(profile.getDns2());
        if (nic.getGateway() != null) {
            to.setGateway(nic.getGateway());
        } else {
            to.setGateway(config.getGateway());
        }
        to.setDefaultNic(nic.isDefaultNic());
        to.setBroadcastUri(nic.getBroadcastUri());
        to.setIsolationuri(nic.getIsolationUri());
        if (profile != null) {
            to.setDns1(profile.getDns1());
            to.setDns2(profile.getDns2());
        }

        Integer networkRate = getNetworkRate(config.getId(), null);
        to.setNetworkRateMbps(networkRate);
        
        to.setUuid(config.getUuid());

        return to;
    }

    @Override
    @DB
    public Pair<NetworkGuru, NetworkVO> implementNetwork(long networkId, DeployDestination dest, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException,
            InsufficientCapacityException {
        Transaction.currentTxn();
        Pair<NetworkGuru, NetworkVO> implemented = new Pair<NetworkGuru, NetworkVO>(null, null);

        NetworkVO network = _networksDao.acquireInLockTable(networkId, _networkLockTimeout);
        if (network == null) {
            // see NetworkVO.java
            ConcurrentOperationException ex = new ConcurrentOperationException("Unable to acquire network configuration");
            ex.addProxyObject("networks", networkId, "networkId");
            throw ex;
        }
        
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Lock is acquired for network id " + networkId + " as a part of network implement");
        }

        try {
            NetworkGuru guru = _networkGurus.get(network.getGuruName());
            Network.State state = network.getState();
            if (state == Network.State.Implemented || state == Network.State.Setup || state == Network.State.Implementing) {
                s_logger.debug("Network id=" + networkId + " is already implemented");
                implemented.set(guru, network);
                return implemented;
            }

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Asking " + guru.getName() + " to implement " + network);
            }

            NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());

            network.setReservationId(context.getReservationId());
            network.setState(Network.State.Implementing);

            _networksDao.update(networkId, network);

            Network result = guru.implement(network, offering, dest, context);
            network.setCidr(result.getCidr());
            network.setBroadcastUri(result.getBroadcastUri());
            network.setGateway(result.getGateway());
            network.setMode(result.getMode());
            network.setPhysicalNetworkId(result.getPhysicalNetworkId());
            _networksDao.update(networkId, network);

            // implement network elements and re-apply all the network rules
            implementNetworkElementsAndResources(dest, context, network, offering);

            network.setState(Network.State.Implemented);
            network.setRestartRequired(false);
            _networksDao.update(network.getId(), network);
            implemented.set(guru, network);
            return implemented;
        } finally {
            if (implemented.first() == null) {
                s_logger.debug("Cleaning up because we're unable to implement the network " + network);
                network.setState(Network.State.Shutdown);
                _networksDao.update(networkId, network);

                shutdownNetwork(networkId, context, false);
            }
            
            _networksDao.releaseFromLockTable(networkId);
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Lock is released for network id " + networkId + " as a part of network implement");
            }
        }
    }

    private void implementNetworkElementsAndResources(DeployDestination dest, ReservationContext context, 
            NetworkVO network, NetworkOfferingVO offering)
            throws ConcurrentOperationException, InsufficientAddressCapacityException, ResourceUnavailableException, InsufficientCapacityException {
        // If this is a 1) guest virtual network 2) network has sourceNat service 3) network offering does not support a
        // Shared source NAT rule,
        // associate a source NAT IP (if one isn't already associated with the network)

        boolean sharedSourceNat = offering.getSharedSourceNat();

        if (network.getGuestType() == Network.GuestType.Isolated 
                && areServicesSupportedInNetwork(network.getId(), Service.SourceNat) 
                && !sharedSourceNat) {
            
            List<IPAddressVO> ips = null;
            if (network.getVpcId() != null) {
                ips = _ipAddressDao.listByAssociatedVpc(network.getVpcId(), true);
                if (ips.isEmpty()) {
                    throw new CloudRuntimeException("Vpc is not implemented; there is no source nat ip");
                }
            } else {
                ips = _ipAddressDao.listByAssociatedNetwork(network.getId(), true);
            }

            if (ips.isEmpty()) {
                s_logger.debug("Creating a source nat ip for network " + network);
                Account owner = _accountMgr.getAccount(network.getAccountId());
                assignSourceNatIpAddressToGuestNetwork(owner, network);
            }
        }

        // get providers to implement
        List<Provider> providersToImplement = getNetworkProviders(network.getId());
        for (NetworkElement element : _networkElements) {
            if (providersToImplement.contains(element.getProvider())) {
                if (!isProviderEnabledInPhysicalNetwork(getPhysicalNetworkId(network), element.getProvider().getName())) {
                    // The physicalNetworkId will not get translated into a uuid by the reponse serializer,
                    // because the serializer would look up the NetworkVO class's table and retrieve the
                    // network id instead of the physical network id.
                    // So just throw this exception as is. We may need to TBD by changing the serializer.
                    throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + 
                            " either doesn't exist or is not enabled in physical network id: " + network.getPhysicalNetworkId());
                }
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Asking " + element.getName() + " to implemenet " + network);
                }
                if (!element.implement(network, offering, dest, context)) {
                    CloudRuntimeException ex = new CloudRuntimeException("Failed to implement provider " + element.getProvider().getName() + " for network with specified id");
                    ex.addProxyObject(network, network.getId(), "networkId");
                    throw ex;                    
                }
            }
        }

        // reapply all the firewall/staticNat/lb rules
        s_logger.debug("Reprogramming network " + network + " as a part of network implement");
        if (!reprogramNetworkRules(network.getId(), UserContext.current().getCaller(), network)) {
            s_logger.warn("Failed to re-program the network as a part of network " + network + " implement");
            // see DataCenterVO.java
            ResourceUnavailableException ex = new ResourceUnavailableException("Unable to apply network rules as a part of network " + network + " implement", DataCenter.class, network.getDataCenterId());
            ex.addProxyObject("data_center", network.getDataCenterId(), "dataCenterId");
            throw ex;
        }
    }

    protected void prepareElement(NetworkElement element, NetworkVO network, 
            NicProfile profile, VirtualMachineProfile<? extends VMInstanceVO> vmProfile,
            DeployDestination dest, ReservationContext context) throws InsufficientCapacityException,
            ConcurrentOperationException, ResourceUnavailableException {
        element.prepare(network, profile, vmProfile, dest, context);
        if (vmProfile.getType() == Type.User && vmProfile.getHypervisorType() != HypervisorType.BareMetal && element.getProvider() != null) {
            if (areServicesSupportedInNetwork(network.getId(), Service.Dhcp) &&
                    isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, element.getProvider()) &&
                    (element instanceof DhcpServiceProvider)) {
                DhcpServiceProvider sp = (DhcpServiceProvider) element;
                sp.addDhcpEntry(network, profile, vmProfile, dest, context);
            }
            if (areServicesSupportedInNetwork(network.getId(), Service.UserData) &&
                    isProviderSupportServiceInNetwork(network.getId(), Service.UserData, element.getProvider()) &&
                    (element instanceof UserDataServiceProvider)) {
                UserDataServiceProvider sp = (UserDataServiceProvider) element;
                sp.addPasswordAndUserdata(network, profile, vmProfile, dest, context);
            }
        }
    }

    @DB
    protected void updateNic(NicVO nic, long networkId, int count) {
        Transaction txn = Transaction.currentTxn();
        txn.start();
        _nicDao.update(nic.getId(), nic);

        if (nic.getVmType() == VirtualMachine.Type.User) {
            s_logger.debug("Changing active number of nics for network id=" + networkId + " on " + count);
            _networksDao.changeActiveNicsBy(networkId, count);
        }

        if (nic.getVmType() == VirtualMachine.Type.User || (nic.getVmType() == VirtualMachine.Type.DomainRouter && getNetwork(networkId).getTrafficType() == TrafficType.Guest)) {
            _networksDao.setCheckForGc(networkId);
        }
        
        txn.commit();
    }

    @Override
    public void prepare(VirtualMachineProfile<? extends VMInstanceVO> vmProfile, DeployDestination dest, ReservationContext context) throws InsufficientCapacityException,
            ConcurrentOperationException, ResourceUnavailableException {
        List<NicVO> nics = _nicDao.listByVmId(vmProfile.getId());

        // we have to implement default nics first - to ensure that default network elements start up first in multiple
        //nics case
        // (need for setting DNS on Dhcp to domR's Ip4 address)
        Collections.sort(nics, new Comparator<NicVO>() {

            @Override
            public int compare(NicVO nic1, NicVO nic2) {
                boolean isDefault1 = nic1.isDefaultNic();
                boolean isDefault2 = nic2.isDefaultNic();

                return (isDefault1 ^ isDefault2) ? ((isDefault1 ^ true) ? 1 : -1) : 0;
            }
        });

        for (NicVO nic : nics) {
            Pair<NetworkGuru, NetworkVO> implemented = implementNetwork(nic.getNetworkId(), dest, context);
            
            NetworkVO network = implemented.second();
            NicProfile profile = prepareNic(vmProfile, dest, context, nic.getId(), network);
            vmProfile.addNic(profile);
        }
    }

    @Override
    public NicProfile prepareNic(VirtualMachineProfile<? extends VMInstanceVO> vmProfile, DeployDestination 
            dest, ReservationContext context, long nicId, NetworkVO network)
            throws InsufficientVirtualNetworkCapcityException, InsufficientAddressCapacityException, 
            ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        
        Integer networkRate = getNetworkRate(network.getId(), vmProfile.getId());
        NetworkGuru guru = _networkGurus.get(network.getGuruName());
        NicVO nic = _nicDao.findById(nicId);
        
        NicProfile profile = null;
        if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start) {
            nic.setState(Nic.State.Reserving);
            nic.setReservationId(context.getReservationId());
            _nicDao.update(nic.getId(), nic);
            URI broadcastUri = nic.getBroadcastUri();
            if (broadcastUri == null) {
                broadcastUri = network.getBroadcastUri();
            }

            URI isolationUri = nic.getIsolationUri();

            profile = new NicProfile(nic, network, broadcastUri, isolationUri, 

            networkRate, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vmProfile.getHypervisorType(), network));
            guru.reserve(profile, network, vmProfile, dest, context);
            nic.setIp4Address(profile.getIp4Address());
            nic.setAddressFormat(profile.getFormat());
            nic.setIp6Address(profile.getIp6Address());
            nic.setMacAddress(profile.getMacAddress());
            nic.setIsolationUri(profile.getIsolationUri());
            nic.setBroadcastUri(profile.getBroadCastUri());
            nic.setReserver(guru.getName());
            nic.setState(Nic.State.Reserved);
            nic.setNetmask(profile.getNetmask());
            nic.setGateway(profile.getGateway());

            if (profile.getStrategy() != null) {
                nic.setReservationStrategy(profile.getStrategy());
            }

            updateNic(nic, network.getId(), 1);
        } else {
            profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 
                        networkRate, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vmProfile.getHypervisorType(), network));
            guru.updateNicProfile(profile, network);
            nic.setState(Nic.State.Reserved);
            updateNic(nic, network.getId(), 1);
        }

        for (NetworkElement element : _networkElements) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Asking " + element.getName() + " to prepare for " + nic);
            }
            prepareElement(element, network, profile, vmProfile, dest, context);
        }

        profile.setSecurityGroupEnabled(isSecurityGroupSupportedInNetwork(network));
        guru.updateNicProfile(profile, network);
        return profile;
    }

    @Override
    public <T extends VMInstanceVO> void prepareNicForMigration(VirtualMachineProfile<T> vm, DeployDestination dest) {
        List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (NicVO nic : nics) {
            NetworkVO network = _networksDao.findById(nic.getNetworkId());
            Integer networkRate = getNetworkRate(network.getId(), vm.getId());

            NetworkGuru guru = _networkGurus.get(network.getGuruName());
            NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate, 
                    isSecurityGroupSupportedInNetwork(network), getNetworkTag(vm.getHypervisorType(), network));
            guru.updateNicProfile(profile, network);
            vm.addNic(profile);
        }
    }

    @Override
    @DB
    public void release(VirtualMachineProfile<? extends VMInstanceVO> vmProfile, boolean forced) throws
            ConcurrentOperationException, ResourceUnavailableException {
        List<NicVO> nics = _nicDao.listByVmId(vmProfile.getId());
        for (NicVO nic : nics) {
            releaseNic(vmProfile, nic);
        }
    }


    @Override
    @DB
    public void releaseNic(VirtualMachineProfile<? extends VMInstanceVO> vmProfile, Nic nic) 
            throws ConcurrentOperationException, ResourceUnavailableException {
        NicVO nicVO = _nicDao.findById(nic.getId());
        releaseNic(vmProfile, nicVO);
    }

    @DB
    protected void releaseNic(VirtualMachineProfile<? extends VMInstanceVO> vmProfile, NicVO nicVO) 
            throws ConcurrentOperationException, ResourceUnavailableException {
        //lock the nic
        Transaction txn = Transaction.currentTxn();
        txn.start();
        
        NicVO nic = _nicDao.lockRow(nicVO.getId(), true);
        if (nic == null) {
            throw new ConcurrentOperationException("Unable to acquire lock on nic " + nic);
        }
        
        Nic.State originalState = nic.getState();
        NetworkVO network = _networksDao.findById(nicVO.getNetworkId());
        
        if (originalState == Nic.State.Reserved || originalState == Nic.State.Reserving) {
            if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start) {
                NetworkGuru guru = _networkGurus.get(network.getGuruName());
                nic.setState(Nic.State.Releasing);
                _nicDao.update(nic.getId(), nic);
                NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), null,
                        isSecurityGroupSupportedInNetwork(network), getNetworkTag(vmProfile.getHypervisorType(), network));
                if (guru.release(profile, vmProfile, nic.getReservationId())) {
                    applyProfileToNicForRelease(nic, profile);
                    nic.setState(Nic.State.Allocated);
                    if (originalState == Nic.State.Reserved) {
                        updateNic(nic, network.getId(), -1);
                    } else {
                        _nicDao.update(nic.getId(), nic);
                    }
                }
                //commit the transaction before proceeding releasing nic profile on the network elements
                txn.commit();
                
                // Perform release on network elements
                for (NetworkElement element : _networkElements) {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Asking " + element.getName() + " to release " + nic);
                    }
                    //NOTE: Context appear to never be used in release method 
                    //implementations. Consider removing it from interface Element
                    element.release(network, profile, vmProfile, null);
                }
                                    
            } else {
                nic.setState(Nic.State.Allocated);
                updateNic(nic, network.getId(), -1);
                txn.commit();
            }
        }
    }

    @Override
    public List<? extends Nic> getNics(long vmId) {
        return _nicDao.listByVmId(vmId);
    }

    @Override
    public List<NicProfile> getNicProfiles(VirtualMachine vm) {
        List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        List<NicProfile> profiles = new ArrayList<NicProfile>();

        if (nics != null) {
            for (Nic nic : nics) {
                NetworkVO network = _networksDao.findById(nic.getNetworkId());
                Integer networkRate = getNetworkRate(network.getId(), vm.getId());

                NetworkGuru guru = _networkGurus.get(network.getGuruName());
                NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 
                        networkRate, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vm.getHypervisorType(), network));
                guru.updateNicProfile(profile, network);
                profiles.add(profile);
            }
        }
        return profiles;
    }
    
    @Override
    public NicProfile getNicProfile(VirtualMachine vm, long networkId, String broadcastUri) {
        NicVO nic = null;
        if (broadcastUri != null) {
            nic = _nicDao.findByNetworkIdInstanceIdAndBroadcastUri(networkId, vm.getId(), broadcastUri);
        } else {
           nic =  _nicDao.findByInstanceIdAndNetworkId(networkId, vm.getId());
        }
        NetworkVO network = _networksDao.findById(networkId);
        Integer networkRate = getNetworkRate(network.getId(), vm.getId());
    
        NetworkGuru guru = _networkGurus.get(network.getGuruName());
        NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 
                networkRate, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vm.getHypervisorType(), network));
        guru.updateNicProfile(profile, network);            
        
        return profile;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_RELEASE, eventDescription = "disassociating Ip", async = true)
    public boolean releaseIpAddress(long ipAddressId) throws InsufficientAddressCapacityException {
        Long userId = UserContext.current().getCallerUserId();
        Account caller = UserContext.current().getCaller();

        // Verify input parameters
        IPAddressVO ipVO = _ipAddressDao.findById(ipAddressId);
        if (ipVO == null) {
            throw new InvalidParameterValueException("Unable to find ip address by id");
        }

        if (ipVO.getAllocatedTime() == null) {
            s_logger.debug("Ip Address id= " + ipAddressId + " is not allocated, so do nothing.");
            return true;
        }

        // verify permissions
        if (ipVO.getAllocatedToAccountId() != null) {
            _accountMgr.checkAccess(caller, null, true, ipVO);
        }

        if (ipVO.isSourceNat()) {
            throw new IllegalArgumentException("ip address is used for source nat purposes and can not be disassociated.");
        }

        VlanVO vlan = _vlanDao.findById(ipVO.getVlanId());
        if (!vlan.getVlanType().equals(VlanType.VirtualNetwork)) {
            throw new IllegalArgumentException("only ip addresses that belong to a virtual network may be disassociated.");
        }

        // Check for account wide pool. It will have an entry for account_vlan_map.
        if (_accountVlanMapDao.findAccountVlanMap(ipVO.getAllocatedToAccountId(), ipVO.getVlanId()) != null) {            
            //see IPaddressVO.java
            InvalidParameterValueException ex = new InvalidParameterValueException("Sepcified IP address uuid belongs to" +
                    " Account wide IP pool and cannot be disassociated");
            ex.addProxyObject("user_ip_address", ipAddressId, "ipAddressId");
            throw ex;
        }

        // don't allow releasing system ip address
        if (ipVO.getSystem()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Can't release system IP address with specified id");
            ex.addProxyObject(ipVO, ipVO.getId(), "systemIpAddrId");
            throw ex;
        }

        boolean success = disassociatePublicIpAddress(ipAddressId, userId, caller);

        if (success) {
            Long networkId = ipVO.getAssociatedWithNetworkId();
            if (networkId != null) {
                Network guestNetwork = getNetwork(networkId);
                NetworkOffering offering = _configMgr.getNetworkOffering(guestNetwork.getNetworkOfferingId());
                Long vmId = ipVO.getAssociatedWithVmId();
                if (offering.getElasticIp() && vmId != null) {
                    _rulesMgr.getSystemIpAndEnableStaticNatForVm(_userVmDao.findById(vmId), true);
                    return true;
                }
            }
        } else {
            s_logger.warn("Failed to release public ip address id=" + ipAddressId);
        }
        return success;
    }

    @Deprecated
    // No one is using this method.
    public AccountVO getNetworkOwner(long networkId) {
        SearchCriteria<AccountVO> sc = AccountsUsingNetworkSearch.create();
        sc.setJoinParameters("nc", "config", networkId);
        sc.setJoinParameters("nc", "owner", true);
        List<AccountVO> accounts = _accountDao.search(sc, null);
        return accounts.size() != 0 ? accounts.get(0) : null;
    }

    @Deprecated
    // No one is using this method.
    public List<NetworkVO> getNetworksforOffering(long offeringId, long dataCenterId, long accountId) {
        return _networksDao.getNetworksForOffering(offeringId, dataCenterId, accountId);
    }

    @Override
    public String getNextAvailableMacAddressInNetwork(long networkId) throws InsufficientAddressCapacityException {
        String mac = _networksDao.getNextAvailableMacAddress(networkId);
        if (mac == null) {
            throw new InsufficientAddressCapacityException("Unable to create another mac address", Network.class, networkId);            
        }
        return mac;
    }

    @Override
    @DB
    public Network getNetwork(long id) {
        return _networksDao.findById(id);
    }

    @Override
    public List<? extends RemoteAccessVPNServiceProvider> getRemoteAccessVpnElements() {
        List<RemoteAccessVPNServiceProvider> elements = new ArrayList<RemoteAccessVPNServiceProvider>();
        for (NetworkElement element : _networkElements) {
            if (element instanceof RemoteAccessVPNServiceProvider) {
                RemoteAccessVPNServiceProvider e = (RemoteAccessVPNServiceProvider) element;
                elements.add(e);
            }
        }

        return elements;
    }

    @Override
    public List<? extends Site2SiteVpnServiceProvider> getSite2SiteVpnElements() {
        List<Site2SiteVpnServiceProvider> elements = new ArrayList<Site2SiteVpnServiceProvider>();
        for (NetworkElement element : _networkElements) {
            if (element instanceof Site2SiteVpnServiceProvider) {
                Site2SiteVpnServiceProvider e = (Site2SiteVpnServiceProvider) element;
                elements.add(e);
            }
        }

        return elements;
    }

    @Override
    public void cleanupNics(VirtualMachineProfile<? extends VMInstanceVO> vm) {
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Cleaning network for vm: " + vm.getId());
        }

        List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (NicVO nic : nics) {
            removeNic(vm, nic);
        }
    }
    
    @Override
    public void removeNic(VirtualMachineProfile<? extends VMInstanceVO> vm, Nic nic) {
        removeNic(vm, _nicDao.findById(nic.getId()));
    }

    protected void removeNic(VirtualMachineProfile<? extends VMInstanceVO> vm, NicVO nic) {
        nic.setState(Nic.State.Deallocating);
        _nicDao.update(nic.getId(), nic);
        NetworkVO network = _networksDao.findById(nic.getNetworkId());
        NicProfile profile = new NicProfile(nic, network, null, null, null,
                isSecurityGroupSupportedInNetwork(network), getNetworkTag(vm.getHypervisorType(), network));
        NetworkGuru guru = _networkGurus.get(network.getGuruName());
        guru.deallocate(network, profile, vm);
        _nicDao.remove(nic.getId());
        s_logger.debug("Removed nic id=" + nic.getId());
    }

    @Override
    public void expungeNics(VirtualMachineProfile<? extends VMInstanceVO> vm) {
        List<NicVO> nics = _nicDao.listByVmIdIncludingRemoved(vm.getId());
        for (NicVO nic : nics) {
            _nicDao.expunge(nic.getId());
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_CREATE, eventDescription = "creating network")
    public Network createGuestNetwork(CreateNetworkCmd cmd) throws InsufficientCapacityException, ConcurrentOperationException, ResourceAllocationException {
        Long networkOfferingId = cmd.getNetworkOfferingId();
        String gateway = cmd.getGateway();
        String startIP = cmd.getStartIp();
        String endIP = cmd.getEndIp();
        String netmask = cmd.getNetmask();
        String networkDomain = cmd.getNetworkDomain();
        String vlanId = cmd.getVlan();
        String name = cmd.getNetworkName();
        String displayText = cmd.getDisplayText();
        Account caller = UserContext.current().getCaller();
        Long physicalNetworkId = cmd.getPhysicalNetworkId();
        Long zoneId = cmd.getZoneId();
        String aclTypeStr = cmd.getAclType();
        Long domainId = cmd.getDomainId();
        boolean isDomainSpecific = false;
        Boolean subdomainAccess = cmd.getSubdomainAccess();
        Long vpcId = cmd.getVpcId();

        // Validate network offering
        NetworkOfferingVO ntwkOff = _networkOfferingDao.findById(networkOfferingId);
        if (ntwkOff == null || ntwkOff.isSystemOnly()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find network offering by specified id");
            if (ntwkOff != null) {
                ex.addProxyObject(ntwkOff, networkOfferingId, "networkOfferingId");                
                // Get the VO object's table name.
                String tablename = AnnotationHelper.getTableName(ntwkOff);
                if (tablename != null) {
                    ex.addProxyObject(tablename, networkOfferingId, "networkOfferingId");
                } else {
                    s_logger.info("\nCould not retrieve table name (annotation) from " + tablename + " VO proxy object\n");
                }
                throw ex;
            }
            throw ex;
        }
        // validate physical network and zone
        // Check if physical network exists
        PhysicalNetwork pNtwk = null;
        if (physicalNetworkId != null) {
            pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find a physical network having the specified physical network id");
            }
        }

        if (zoneId == null) {
            zoneId = pNtwk.getDataCenterId();
        }

        DataCenter zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Specified zone id was not found");
        }
        
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getType())) {
            // See DataCenterVO.java
            PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation since specified Zone is currently disabled");
            ex.addProxyObject(zone, zoneId, "zoneId");
            throw ex;            
        }

        // Only domain and account ACL types are supported in Acton.
        ACLType aclType = null;
        if (aclTypeStr != null) {
            if (aclTypeStr.equalsIgnoreCase(ACLType.Account.toString())) {
                aclType = ACLType.Account;
            } else if (aclTypeStr.equalsIgnoreCase(ACLType.Domain.toString())) {
                aclType = ACLType.Domain;
            } else {
                throw new InvalidParameterValueException("Incorrect aclType specified. Check the API documentation for supported types");
            }
            // In 3.0 all Shared networks should have aclType == Domain, all Isolated networks aclType==Account
            if (ntwkOff.getGuestType() == GuestType.Isolated) {
                if (aclType != ACLType.Account) {
                    throw new InvalidParameterValueException("AclType should be " + ACLType.Account + " for network of type " + Network.GuestType.Isolated);
                }
            } else if (ntwkOff.getGuestType() == GuestType.Shared) {
                if (!(aclType == ACLType.Domain || aclType == ACLType.Account)) {
                    throw new InvalidParameterValueException("AclType should be " + ACLType.Domain + " or " + 
                ACLType.Account + " for network of type " + Network.GuestType.Shared);
                }
            }
        } else {
            if (ntwkOff.getGuestType() == GuestType.Isolated) {
                aclType = ACLType.Account;
            } else if (ntwkOff.getGuestType() == GuestType.Shared) {
                aclType = ACLType.Domain;
            }
        }

        // Only Admin can create Shared networks
        if (ntwkOff.getGuestType() == GuestType.Shared && !_accountMgr.isAdmin(caller.getType())) {
            throw new InvalidParameterValueException("Only Admins can create network with guest type " + GuestType.Shared);
        }

        // Check if the network is domain specific
        if (aclType == ACLType.Domain) {
            // only Admin can create domain with aclType=Domain
            if (!_accountMgr.isAdmin(caller.getType())) {
                throw new PermissionDeniedException("Only admin can create networks with aclType=Domain");
            }

            // only shared networks can be Domain specific
            if (ntwkOff.getGuestType() != GuestType.Shared) {
                throw new InvalidParameterValueException("Only " + GuestType.Shared + " networks can have aclType=" + ACLType.Domain);
            }

            if (domainId != null) {
                if (ntwkOff.getTrafficType() != TrafficType.Guest || ntwkOff.getGuestType() != Network.GuestType.Shared) {
                    throw new InvalidParameterValueException("Domain level networks are supported just for traffic type "
                + TrafficType.Guest + " and guest type " + Network.GuestType.Shared);
                }

                DomainVO domain = _domainDao.findById(domainId);
                if (domain == null) {                    
                    throw new InvalidParameterValueException("Unable to find domain by specified id");
                }
                _accountMgr.checkAccess(caller, domain);
            }
            isDomainSpecific = true;

        } else if (subdomainAccess != null) {
            throw new InvalidParameterValueException("Parameter subDomainAccess can be specified only with aclType=Domain");
        }
        Account owner = null;
        if ((cmd.getAccountName() != null && domainId != null) || cmd.getProjectId() != null) {
            owner = _accountMgr.finalizeOwner(caller, cmd.getAccountName(), domainId, cmd.getProjectId());
        } else {
            owner = caller;
        }

        UserContext.current().setAccountId(owner.getAccountId());

        // VALIDATE IP INFO
        // if end ip is not specified, default it to startIp
        if (startIP != null) {
            if (!NetUtils.isValidIp(startIP)) {
                throw new InvalidParameterValueException("Invalid format for the startIp parameter");
            }
            if (endIP == null) {
                endIP = startIP;
            } else if (!NetUtils.isValidIp(endIP)) {
                throw new InvalidParameterValueException("Invalid format for the endIp parameter");
            }
        }

        if (startIP != null && endIP != null) {
            if (!(gateway != null && netmask != null)) {
                throw new InvalidParameterValueException("gateway and netmask should be defined when startIP/endIP are passed in");
            }
        }

        String cidr = null;
        if (gateway != null && netmask != null) {
            if (!NetUtils.isValidIp(gateway)) {
                throw new InvalidParameterValueException("Invalid gateway");
            }
            if (!NetUtils.isValidNetmask(netmask)) {
                throw new InvalidParameterValueException("Invalid netmask");
            }

            cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);
        }

        // Regular user can create Guest Isolated Source Nat enabled network only
        if (caller.getType() == Account.ACCOUNT_TYPE_NORMAL
                && (ntwkOff.getTrafficType() != TrafficType.Guest || ntwkOff.getGuestType() != Network.GuestType.Isolated
                        && areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat))) {
            throw new InvalidParameterValueException("Regular user can create a network only from the network" +
                    " offering having traffic type " + TrafficType.Guest + " and network type "
                    + Network.GuestType.Isolated + " with a service " + Service.SourceNat.getName() + " enabled");
        }

        // Don't allow to specify vlan if the caller is a regular user
        if (caller.getType() == Account.ACCOUNT_TYPE_NORMAL && (ntwkOff.getSpecifyVlan() || vlanId != null)) {
            throw new InvalidParameterValueException("Regular user is not allowed to specify vlanId");
        }

        // For non-root admins check cidr limit - if it's allowed by global config value
        if (caller.getType() != Account.ACCOUNT_TYPE_ADMIN && cidr != null) {

            String[] cidrPair = cidr.split("\\/");
            int cidrSize = Integer.valueOf(cidrPair[1]);

            if (cidrSize < _cidrLimit) {
                throw new InvalidParameterValueException("Cidr size can't be less than " + _cidrLimit);
            }
        }

        if (cidr != null && networkOfferingIsConfiguredForExternalNetworking(networkOfferingId)) {
            throw new InvalidParameterValueException("Cannot specify CIDR when using network offering with external devices!");
        }

        // Vlan is created in 2 cases - works in Advance zone only:
        // 1) GuestType is Shared
        // 2) GuestType is Isolated, but SourceNat service is disabled
        boolean createVlan = (startIP != null && endIP != null && zone.getNetworkType() == NetworkType.Advanced
                && ((ntwkOff.getGuestType() == Network.GuestType.Shared)
                || (ntwkOff.getGuestType() == GuestType.Isolated && 
                !areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat))));

        // Can add vlan range only to the network which allows it
        if (createVlan && !ntwkOff.getSpecifyIpRanges()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Network offering with specified id doesn't support adding multiple ip ranges");
            ex.addProxyObject(ntwkOff, ntwkOff.getId(), "networkOfferingId");
            String tablename = AnnotationHelper.getTableName(ntwkOff);
            if (tablename != null) {
                ex.addProxyObject(tablename, ntwkOff.getId(), "networkOfferingId");
            } else {
                s_logger.info("\nCould not retrieve table name (annotation) from " + tablename + " VO proxy object\n");
            }
            throw ex;   
        }

        Transaction txn = Transaction.currentTxn();
        txn.start();

        Long sharedDomainId = null;
        if (isDomainSpecific) {
            if (domainId != null) {
                sharedDomainId = domainId;
            } else {
                sharedDomainId = _domainMgr.getDomain(Domain.ROOT_DOMAIN).getId();
                subdomainAccess = true;
            }
        }

        // default owner to system if network has aclType=Domain
        if (aclType == ACLType.Domain) {
            owner = _accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM);
        }

        //Create guest network
        Network network = null;
        if (vpcId != null) {
            if (!_configMgr.isOfferingForVpc(ntwkOff)){
                throw new InvalidParameterValueException("Network offering can't be used for VPC networks");
            }
            network = _vpcMgr.createVpcGuestNetwork(networkOfferingId, name, displayText, gateway, cidr, vlanId, 
                    networkDomain, owner, sharedDomainId, pNtwk, zoneId, aclType, subdomainAccess, vpcId, caller);
        } else {
            if (_configMgr.isOfferingForVpc(ntwkOff)){
                throw new InvalidParameterValueException("Network offering can be used for VPC networks only");
            }
            network = createGuestNetwork(networkOfferingId, name, displayText, gateway, cidr, vlanId, 
                    networkDomain, owner, sharedDomainId, pNtwk, zoneId, aclType, subdomainAccess, vpcId);
        }  

        if (caller.getType() == Account.ACCOUNT_TYPE_ADMIN && createVlan) {
            // Create vlan ip range
            _configMgr.createVlanAndPublicIpRange(pNtwk.getDataCenterId(), network.getId(), physicalNetworkId,
                    false, null, startIP, endIP, gateway, netmask, vlanId, null);
        }

        txn.commit();

        return network;
    }

    @Override
    @DB
    public Network createGuestNetwork(long networkOfferingId, String name, String displayText, String gateway, 
            String cidr, String vlanId, String networkDomain, Account owner, Long domainId,
            PhysicalNetwork pNtwk, long zoneId, ACLType aclType, Boolean subdomainAccess, Long vpcId) 
                    throws ConcurrentOperationException, InsufficientCapacityException, ResourceAllocationException {

        NetworkOfferingVO ntwkOff = _networkOfferingDao.findById(networkOfferingId);
        // this method supports only guest network creation
        if (ntwkOff.getTrafficType() != TrafficType.Guest) {
            s_logger.warn("Only guest networks can be created using this method");
            return null;
        }

        boolean updateResourceCount = resourceCountNeedsUpdate(ntwkOff, aclType);
        //check resource limits
        if (updateResourceCount) {
            _resourceLimitMgr.checkResourceLimit(owner, ResourceType.network);
        }

        // Validate network offering
        if (ntwkOff.getState() != NetworkOffering.State.Enabled) {
            // see NetworkOfferingVO
            InvalidParameterValueException ex = new InvalidParameterValueException("Can't use specified network offering id as its stat is not " + NetworkOffering.State.Enabled);
            ex.addProxyObject(ntwkOff, ntwkOff.getId(), "networkOfferingId");
            throw ex;
        }

        // Validate physical network
        if (pNtwk.getState() != PhysicalNetwork.State.Enabled) {
            // see PhysicalNetworkVO.java
            InvalidParameterValueException ex = new InvalidParameterValueException("Specified physical network id is" +
                    " in incorrect state:" + pNtwk.getState());
            ex.addProxyObject("physical_network", pNtwk.getId(), "physicalNetworkId");
            throw ex;
        }

        // Validate zone
        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone.getNetworkType() == NetworkType.Basic) {
            // In Basic zone the network should have aclType=Domain, domainId=1, subdomainAccess=true
            if (aclType == null || aclType != ACLType.Domain) {
                throw new InvalidParameterValueException("Only AclType=Domain can be specified for network creation in Basic zone");
            }
            
            // Only one guest network is supported in Basic zone
            List<NetworkVO> guestNetworks = _networksDao.listByZoneAndTrafficType(zone.getId(), TrafficType.Guest);
            if (!guestNetworks.isEmpty()) {
                throw new InvalidParameterValueException("Can't have more than one Guest network in zone with network type "
                                                        + NetworkType.Basic);
            }

            // if zone is basic, only Shared network offerings w/o source nat service are allowed
            if (!(ntwkOff.getGuestType() == GuestType.Shared && 
                    !areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat))) {
                throw new InvalidParameterValueException("For zone of type " + NetworkType.Basic + " only offerings of " +
                        "guestType " + GuestType.Shared + " with disabled " + Service.SourceNat.getName()
                        + " service are allowed");
            }

            if (domainId == null || domainId != Domain.ROOT_DOMAIN) {
                throw new InvalidParameterValueException("Guest network in Basic zone should be dedicated to ROOT domain");
            }

            if (subdomainAccess == null) {
                subdomainAccess = true;
            } else if (!subdomainAccess) {
                throw new InvalidParameterValueException("Subdomain access should be set to true for the" +
                        " guest network in the Basic zone");
            }

            if (vlanId == null) {
                vlanId = Vlan.UNTAGGED;
            } else {
                if (!vlanId.equalsIgnoreCase(Vlan.UNTAGGED)) {
                    throw new InvalidParameterValueException("Only vlan " + Vlan.UNTAGGED + " can be created in " +
                            "the zone of type " + NetworkType.Basic);
                }
            }

        } else if (zone.getNetworkType() == NetworkType.Advanced) {
            if (zone.isSecurityGroupEnabled()) {
                // Only Account specific Isolated network with sourceNat service disabled are allowed in security group
                // enabled zone
                boolean allowCreation = (ntwkOff.getGuestType() == GuestType.Isolated 
                        && !areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat));
                if (!allowCreation) {
                    throw new InvalidParameterValueException("Only Account specific Isolated network with sourceNat " +
                            "service disabled are allowed in security group enabled zone");
                }
            }
            
            //don't allow eip/elb networks in Advance zone
            if (ntwkOff.getElasticIp() || ntwkOff.getElasticLb()) {
                throw new InvalidParameterValueException("Elastic IP and Elastic LB services are supported in zone of type " + NetworkType.Basic);
            }
        }

        // VlanId can be specified only when network offering supports it
        boolean vlanSpecified = (vlanId != null);
        if (vlanSpecified != ntwkOff.getSpecifyVlan()) {
            if (vlanSpecified) {
                throw new InvalidParameterValueException("Can't specify vlan; corresponding offering says specifyVlan=false");
            } else {
                throw new InvalidParameterValueException("Vlan has to be specified; corresponding offering says specifyVlan=true");
            }
        }

        if (vlanId != null) {
            String uri = "vlan://" + vlanId;
            // For Isolated networks, don't allow to create network with vlan that already exists in the zone
            if (ntwkOff.getGuestType() == GuestType.Isolated) {
                if (_networksDao.countByZoneAndUri(zoneId, uri) > 0) {
                throw new InvalidParameterValueException("Network with vlan " + vlanId + " already exists in zone " + zoneId);
            }
            } else {
                //don't allow to create Shared network with Vlan that already exists in the zone for Isolated networks
                if (_networksDao.countByZoneUriAndGuestType(zoneId, uri, GuestType.Isolated) > 0) {
                    throw new InvalidParameterValueException("Isolated network with vlan " + vlanId + " already exists " +
                            "in zone " + zoneId);
                }
        }
        }
        
        // If networkDomain is not specified, take it from the global configuration
        if (areServicesSupportedByNetworkOffering(networkOfferingId, Service.Dns)) {
            Map<Network.Capability, String> dnsCapabilities = getNetworkOfferingServiceCapabilities
                    (_configMgr.getNetworkOffering(networkOfferingId), Service.Dns);
            String isUpdateDnsSupported = dnsCapabilities.get(Capability.AllowDnsSuffixModification);
            if (isUpdateDnsSupported == null || !Boolean.valueOf(isUpdateDnsSupported)) {
                if (networkDomain != null) {
                    // TBD: NetworkOfferingId and zoneId. Send uuids instead.
                    throw new InvalidParameterValueException("Domain name change is not supported by network offering id="
                            + networkOfferingId + " in zone id=" + zoneId);
                }
            } else {
                if (networkDomain == null) {
                    // 1) Get networkDomain from the corresponding account/domain/zone
                    if (aclType == ACLType.Domain) {
                        networkDomain = getDomainNetworkDomain(domainId, zoneId);
                    } else if (aclType == ACLType.Account) {
                        networkDomain = getAccountNetworkDomain(owner.getId(), zoneId);
                    }

                    // 2) If null, generate networkDomain using domain suffix from the global config variables
                    if (networkDomain == null) {
                        networkDomain = "cs" + Long.toHexString(owner.getId()) + _networkDomain;
                    }

                } else {
                    // validate network domain
                    if (!NetUtils.verifyDomainName(networkDomain)) {
                        throw new InvalidParameterValueException(
                                "Invalid network domain. Total length shouldn't exceed 190 chars. Each domain " +
                                "label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                        + "and the hyphen ('-'); can't start or end with \"-\"");
                    }
                }
            }
        }

        // In Advance zone Cidr for Shared networks and Isolated networks w/o source nat service can't be NULL - 2.2.x
        // limitation, remove after we introduce support for multiple ip ranges
        // with different Cidrs for the same Shared network
        boolean cidrRequired = zone.getNetworkType() == NetworkType.Advanced && ntwkOff.getTrafficType() == TrafficType.Guest
                && (ntwkOff.getGuestType() == GuestType.Shared || (ntwkOff.getGuestType() == GuestType.Isolated 
                && !areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat)));
        if (cidr == null && cidrRequired) {
            throw new InvalidParameterValueException("StartIp/endIp/gateway/netmask are required when create network of" +
                    " type " + Network.GuestType.Shared + " and network of type " + GuestType.Isolated + " with service "
                    + Service.SourceNat.getName() + " disabled");
        }

        // No cidr can be specified in Basic zone
        if (zone.getNetworkType() == NetworkType.Basic && cidr != null) {
            throw new InvalidParameterValueException("StartIp/endIp/gateway/netmask can't be specified for zone of type " + NetworkType.Basic);
        }

        // Check if cidr is RFC1918 compliant if the network is Guest Isolated
        if (cidr != null && ntwkOff.getGuestType() == Network.GuestType.Isolated && ntwkOff.getTrafficType() == TrafficType.Guest) {
            if (!NetUtils.validateGuestCidr(cidr)) {
                throw new InvalidParameterValueException("Virtual Guest Cidr " + cidr + " is not RFC1918 compliant");
            }
        }

        Transaction txn = Transaction.currentTxn();
        txn.start();

        Long physicalNetworkId = null;
        if (pNtwk != null) {
            physicalNetworkId = pNtwk.getId();
        }
        DataCenterDeployment plan = new DataCenterDeployment(zoneId, null, null, null, null, physicalNetworkId);
        NetworkVO userNetwork = new NetworkVO();
        userNetwork.setNetworkDomain(networkDomain);

        if (cidr != null && gateway != null) {
            userNetwork.setCidr(cidr);
            userNetwork.setGateway(gateway);
            if (vlanId != null) {
                userNetwork.setBroadcastUri(URI.create("vlan://" + vlanId));
                userNetwork.setBroadcastDomainType(BroadcastDomainType.Vlan);
                if (!vlanId.equalsIgnoreCase(Vlan.UNTAGGED)) {
                    userNetwork.setBroadcastDomainType(BroadcastDomainType.Vlan);
                } else {
                    userNetwork.setBroadcastDomainType(BroadcastDomainType.Native);
                }
            }
        }

        List<NetworkVO> networks = setupNetwork(owner, ntwkOff, userNetwork, plan, name, displayText, true, domainId,
                aclType, subdomainAccess, vpcId);

        Network network = null;
        if (networks == null || networks.isEmpty()) {
            throw new CloudRuntimeException("Fail to create a network");
        } else {
            if (networks.size() > 0 && networks.get(0).getGuestType() == Network.GuestType.Isolated && 
                    networks.get(0).getTrafficType() == TrafficType.Guest) {
                Network defaultGuestNetwork = networks.get(0);
                for (Network nw : networks) {
                    if (nw.getCidr() != null && nw.getCidr().equals(zone.getGuestNetworkCidr())) {
                        defaultGuestNetwork = nw;
                    }
                }
                network = defaultGuestNetwork;
            } else {
                // For shared network
                network = networks.get(0);
            }
        }
        
        if (updateResourceCount) {
            _resourceLimitMgr.incrementResourceCount(owner.getId(), ResourceType.network);
        }

        txn.commit();
        UserContext.current().setEventDetails("Network Id: " + network.getId());
        return network;
    }

    @Override
    public List<? extends Network> searchForNetworks(ListNetworksCmd cmd) {
        Long id = cmd.getId();
        String keyword = cmd.getKeyword();
        Long zoneId = cmd.getZoneId();
        Account caller = UserContext.current().getCaller();
        Long domainId = cmd.getDomainId();
        String accountName = cmd.getAccountName();
        String guestIpType = cmd.getGuestIpType();
        String trafficType = cmd.getTrafficType();
        Boolean isSystem = cmd.getIsSystem();
        String aclType = cmd.getAclType();
        Long projectId = cmd.getProjectId();
        List<Long> permittedAccounts = new ArrayList<Long>();
        String path = null;
        Long physicalNetworkId = cmd.getPhysicalNetworkId();
        List<String> supportedServicesStr = cmd.getSupportedServices();
        Boolean restartRequired = cmd.getRestartRequired();
        boolean listAll = cmd.listAll();
        boolean isRecursive = cmd.isRecursive();
        Boolean specifyIpRanges = cmd.getSpecifyIpRanges();
        Long vpcId = cmd.getVpcId();
        Boolean canUseForDeploy = cmd.canUseForDeploy();
        Map<String, String> tags = cmd.getTags();
        Boolean forVpc = cmd.getForVpc();

        // 1) default is system to false if not specified
        // 2) reset parameter to false if it's specified by the regular user
        if ((isSystem == null || caller.getType() == Account.ACCOUNT_TYPE_NORMAL) && id == null) {
            isSystem = false;
        }

        // Account/domainId parameters and isSystem are mutually exclusive
        if (isSystem != null && isSystem && (accountName != null || domainId != null)) {
            throw new InvalidParameterValueException("System network belongs to system, account and domainId parameters can't be specified");
        }

        if (domainId != null) {
            DomainVO domain = _domainDao.findById(domainId);
            if (domain == null) {
                // see DomainVO.java
                throw new InvalidParameterValueException("Specified domain id doesn't exist in the system");
            }

            _accountMgr.checkAccess(caller, domain);
            if (accountName != null) {
                Account owner = _accountMgr.getActiveAccountByName(accountName, domainId);
                if (owner == null) {
                    // see DomainVO.java
                    throw new InvalidParameterValueException("Unable to find account " + accountName + " in specified domain");
                }

                _accountMgr.checkAccess(caller, null, true, owner);
                permittedAccounts.add(owner.getId());
            }
        }

        if (!_accountMgr.isAdmin(caller.getType()) || (!listAll && (projectId != null && projectId.longValue() != -1 && domainId == null))) {
            permittedAccounts.add(caller.getId());
            domainId = caller.getDomainId();
        }

        // set project information
        boolean skipProjectNetworks = true;
        if (projectId != null) {
            if (projectId.longValue() == -1) {
                if (!_accountMgr.isAdmin(caller.getType())) {
                    permittedAccounts.addAll(_projectMgr.listPermittedProjectAccounts(caller.getId()));
                }
            } else {
                permittedAccounts.clear();
                Project project = _projectMgr.getProject(projectId);
                if (project == null) {                    
                    throw new InvalidParameterValueException("Unable to find project by specified id");
                }
                if (!_projectMgr.canAccessProjectAccount(caller, project.getProjectAccountId())) {
                    // getProject() returns type ProjectVO.
                    InvalidParameterValueException ex = new InvalidParameterValueException("Account " + caller + " cannot access specified project id");
                    ex.addProxyObject(project, projectId, "projectId");                    
                    throw ex;
                }
                permittedAccounts.add(project.getProjectAccountId());
            }
            skipProjectNetworks = false;
        }

        if (domainId != null) {
            path = _domainDao.findById(domainId).getPath();
        } else {
        path = _domainDao.findById(caller.getDomainId()).getPath();
        } 
        
        if (listAll && domainId == null) {
            isRecursive = true;
        }

        Filter searchFilter = new Filter(NetworkVO.class, "id", false, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<NetworkVO> sb = _networksDao.createSearchBuilder();
        
        if (forVpc != null) {
            if (forVpc) {
                sb.and("vpc", sb.entity().getVpcId(), Op.NNULL);
            } else {
                sb.and("vpc", sb.entity().getVpcId(), Op.NULL);
            }
        }

        // Don't display networks created of system network offerings
        SearchBuilder<NetworkOfferingVO> networkOfferingSearch = _networkOfferingDao.createSearchBuilder();
        networkOfferingSearch.and("systemOnly", networkOfferingSearch.entity().isSystemOnly(), SearchCriteria.Op.EQ);
        if (isSystem != null && isSystem) {
            networkOfferingSearch.and("trafficType", networkOfferingSearch.entity().getTrafficType(), SearchCriteria.Op.EQ);
        }
        sb.join("networkOfferingSearch", networkOfferingSearch, sb.entity().getNetworkOfferingId(), networkOfferingSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        SearchBuilder<DataCenterVO> zoneSearch = _dcDao.createSearchBuilder();
        zoneSearch.and("networkType", zoneSearch.entity().getNetworkType(), SearchCriteria.Op.EQ);
        sb.join("zoneSearch", zoneSearch, sb.entity().getDataCenterId(), zoneSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        sb.and("removed", sb.entity().getRemoved(), Op.NULL);

        if (tags != null && !tags.isEmpty()) {
            SearchBuilder<ResourceTagVO> tagSearch = _resourceTagDao.createSearchBuilder();
            for (int count=0; count < tags.size(); count++) {
                tagSearch.or().op("key" + String.valueOf(count), tagSearch.entity().getKey(), SearchCriteria.Op.EQ);
                tagSearch.and("value" + String.valueOf(count), tagSearch.entity().getValue(), SearchCriteria.Op.EQ);
                tagSearch.cp();
            }
            tagSearch.and("resourceType", tagSearch.entity().getResourceType(), SearchCriteria.Op.EQ);
            sb.groupBy(sb.entity().getId());
            sb.join("tagSearch", tagSearch, sb.entity().getId(), tagSearch.entity().getResourceId(), JoinBuilder.JoinType.INNER);
        }

        if (permittedAccounts.isEmpty()) {
            SearchBuilder<DomainVO> domainSearch = _domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
            sb.join("domainSearch", domainSearch, sb.entity().getDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }


            SearchBuilder<AccountVO> accountSearch = _accountDao.createSearchBuilder();
        accountSearch.and("typeNEQ", accountSearch.entity().getType(), SearchCriteria.Op.NEQ);
        accountSearch.and("typeEQ", accountSearch.entity().getType(), SearchCriteria.Op.EQ);
        
        
            sb.join("accountSearch", accountSearch, sb.entity().getAccountId(), accountSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        List<NetworkVO> networksToReturn = new ArrayList<NetworkVO>();

        if (isSystem == null || !isSystem) {
            if (!permittedAccounts.isEmpty()) {
                //get account level networks
                networksToReturn.addAll(listAccountSpecificNetworks(
                        buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId, guestIpType, trafficType, 
                                physicalNetworkId, aclType, skipProjectNetworks, restartRequired, specifyIpRanges, vpcId, tags), searchFilter,
                        permittedAccounts));
                //get domain level networks
                if (domainId != null) {
                    networksToReturn
                    .addAll(listDomainLevelNetworks(
                            buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId, guestIpType, trafficType,
                                    physicalNetworkId, aclType, true, restartRequired, specifyIpRanges, vpcId, tags), searchFilter,
                                    domainId, false));
                }
            } else {
                //add account specific networks
                networksToReturn.addAll(listAccountSpecificNetworksByDomainPath(
                        buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId, guestIpType, trafficType, 
                                physicalNetworkId, aclType, skipProjectNetworks, restartRequired, specifyIpRanges, vpcId, tags), searchFilter, path,
                        isRecursive));
                //add domain specific networks of domain + parent domains
                networksToReturn.addAll(listDomainSpecificNetworksByDomainPath(
                        buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId, guestIpType, trafficType, 
                                physicalNetworkId, aclType, skipProjectNetworks, restartRequired, specifyIpRanges, vpcId, tags), searchFilter, path,
                                isRecursive));
                //add networks of subdomains
                if (domainId == null) {
                    networksToReturn
                    .addAll(listDomainLevelNetworks(
                            buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId, guestIpType, trafficType,
                                    physicalNetworkId, aclType, true, restartRequired, specifyIpRanges, vpcId, tags), searchFilter,
                                    caller.getDomainId(), true));
                }
            }
        } else {
            networksToReturn = _networksDao.search(buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId,
                    guestIpType, trafficType, physicalNetworkId, null, skipProjectNetworks, restartRequired, specifyIpRanges, vpcId, tags),
                    searchFilter);
        }

        if (supportedServicesStr != null && !supportedServicesStr.isEmpty() && !networksToReturn.isEmpty()) {
            List<NetworkVO> supportedNetworks = new ArrayList<NetworkVO>();
            Service[] suppportedServices = new Service[supportedServicesStr.size()];
            int i = 0;
            for (String supportedServiceStr : supportedServicesStr) {
                Service service = Service.getService(supportedServiceStr);
                if (service == null) {
                    throw new InvalidParameterValueException("Invalid service specified " + supportedServiceStr);
                } else {
                    suppportedServices[i] = service;
                }
                i++;
            }

            for (NetworkVO network : networksToReturn) {
                if (areServicesSupportedInNetwork(network.getId(), suppportedServices)) {
                    supportedNetworks.add(network);
                }
            }

            networksToReturn=supportedNetworks;
        }
        
        if (canUseForDeploy != null) {
            List<NetworkVO> networksForDeploy = new ArrayList<NetworkVO>();
            for (NetworkVO network : networksToReturn) {
                if (canUseForDeploy(network) == canUseForDeploy) {
                    networksForDeploy.add(network);
                }
            }
            
            networksToReturn=networksForDeploy;
        }
        
            return networksToReturn;
        }

    @Override
    public boolean canUseForDeploy(Network network) {
        if (network.getTrafficType() != TrafficType.Guest) {
            return false;
        }
        boolean hasFreeIps = true;
        if (network.getGuestType() == GuestType.Shared) {
            hasFreeIps = _ipAddressDao.countFreeIPsInNetwork(network.getId()) > 0;
        } else {
            hasFreeIps = (getAvailableIps(network, null)).size() > 0;
        }
       
        return hasFreeIps;
    }

    private SearchCriteria<NetworkVO> buildNetworkSearchCriteria(SearchBuilder<NetworkVO> sb, String keyword, Long id, 
            Boolean isSystem, Long zoneId, String guestIpType, String trafficType, Long physicalNetworkId,
            String aclType, boolean skipProjectNetworks, Boolean restartRequired, Boolean specifyIpRanges, Long vpcId, Map<String, String> tags) {

        SearchCriteria<NetworkVO> sc = sb.create();

        if (isSystem != null) {
            sc.setJoinParameters("networkOfferingSearch", "systemOnly", isSystem);
        }

        if (keyword != null) {
            SearchCriteria<NetworkVO> ssc = _networksDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (zoneId != null) {
            sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        }

        if (guestIpType != null) {
            sc.addAnd("guestType", SearchCriteria.Op.EQ, guestIpType);
        }

        if (trafficType != null) {
            sc.addAnd("trafficType", SearchCriteria.Op.EQ, trafficType);
        }

        if (aclType != null) {
            sc.addAnd("aclType", SearchCriteria.Op.EQ, aclType.toString());
        }

        if (physicalNetworkId != null) {
            sc.addAnd("physicalNetworkId", SearchCriteria.Op.EQ, physicalNetworkId);
        }

        if (skipProjectNetworks) {
            sc.setJoinParameters("accountSearch", "typeNEQ", Account.ACCOUNT_TYPE_PROJECT);
        } else {
            sc.setJoinParameters("accountSearch", "typeEQ", Account.ACCOUNT_TYPE_PROJECT);
        }

        if (restartRequired != null) {
            sc.addAnd("restartRequired", SearchCriteria.Op.EQ, restartRequired);
        }

        if (specifyIpRanges != null) {
            sc.addAnd("specifyIpRanges", SearchCriteria.Op.EQ, specifyIpRanges);
        }
        
        if (vpcId != null) {
            sc.addAnd("vpcId", SearchCriteria.Op.EQ, vpcId);
        }
        
        if (tags != null && !tags.isEmpty()) {
            int count = 0;
            sc.setJoinParameters("tagSearch", "resourceType", TaggedResourceType.Network.toString());
            for (String key : tags.keySet()) {
                sc.setJoinParameters("tagSearch", "key" + String.valueOf(count), key);
                sc.setJoinParameters("tagSearch", "value" + String.valueOf(count), tags.get(key));
                count++;
            }
        }

        return sc;
    }

    private List<NetworkVO> listDomainLevelNetworks(SearchCriteria<NetworkVO> sc, Filter searchFilter, long domainId, boolean parentDomainsOnly) {
        List<Long> networkIds = new ArrayList<Long>();
        Set<Long> allowedDomains = _domainMgr.getDomainParentIds(domainId);
        List<NetworkDomainVO> maps = _networkDomainDao.listDomainNetworkMapByDomain(allowedDomains.toArray());

        for (NetworkDomainVO map : maps) {
            if (map.getDomainId() == domainId && parentDomainsOnly) {
                continue;
            }
            boolean subdomainAccess = (map.isSubdomainAccess() != null) ? map.isSubdomainAccess() : getAllowSubdomainAccessGlobal();
            if (map.getDomainId() == domainId || subdomainAccess) {
                networkIds.add(map.getNetworkId());
            }
        }

        if (!networkIds.isEmpty()) {
            SearchCriteria<NetworkVO> domainSC = _networksDao.createSearchCriteria();
            domainSC.addAnd("id", SearchCriteria.Op.IN, networkIds.toArray());
            domainSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Domain.toString());

            sc.addAnd("id", SearchCriteria.Op.SC, domainSC);
            return _networksDao.search(sc, searchFilter);
        } else {
            return new ArrayList<NetworkVO>();
        }
    }

    private List<NetworkVO> listAccountSpecificNetworks(SearchCriteria<NetworkVO> sc, Filter searchFilter, List<Long> permittedAccounts) {
        SearchCriteria<NetworkVO> accountSC = _networksDao.createSearchCriteria();
        if (!permittedAccounts.isEmpty()) {
            accountSC.addAnd("accountId", SearchCriteria.Op.IN, permittedAccounts.toArray());
        }

        accountSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Account.toString());

        sc.addAnd("id", SearchCriteria.Op.SC, accountSC);
        return _networksDao.search(sc, searchFilter);
    }

    private List<NetworkVO> listAccountSpecificNetworksByDomainPath(SearchCriteria<NetworkVO> sc, Filter searchFilter, String path, boolean isRecursive) {
        SearchCriteria<NetworkVO> accountSC = _networksDao.createSearchCriteria();
        accountSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Account.toString());

        if (path != null) {
            if (isRecursive) {
                sc.setJoinParameters("domainSearch", "path", path + "%");
            } else {
                sc.setJoinParameters("domainSearch", "path", path);
            }
        }

        sc.addAnd("id", SearchCriteria.Op.SC, accountSC);
        return _networksDao.search(sc, searchFilter);
    }

    private List<NetworkVO> listDomainSpecificNetworksByDomainPath(SearchCriteria<NetworkVO> sc, Filter searchFilter,
            String path, boolean isRecursive) {

        Set<Long> allowedDomains = new HashSet<Long>();
        if (path != null) {
            if (isRecursive) {
                allowedDomains = _domainMgr.getDomainChildrenIds(path);
            } else {
                Domain domain = _domainDao.findDomainByPath(path);
                allowedDomains.add(domain.getId());
            }
        }

        List<Long> networkIds = new ArrayList<Long>();
        
        List<NetworkDomainVO> maps = _networkDomainDao.listDomainNetworkMapByDomain(allowedDomains.toArray());

        for (NetworkDomainVO map : maps) {
            networkIds.add(map.getNetworkId());
        }

        if (!networkIds.isEmpty()) {
            SearchCriteria<NetworkVO> domainSC = _networksDao.createSearchCriteria();
            domainSC.addAnd("id", SearchCriteria.Op.IN, networkIds.toArray());
            domainSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Domain.toString());

            sc.addAnd("id", SearchCriteria.Op.SC, domainSC);
        return _networksDao.search(sc, searchFilter);
        } else {
            return new ArrayList<NetworkVO>();
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_DELETE, eventDescription = "deleting network", async = true)
    public boolean deleteNetwork(long networkId) {

        Account caller = UserContext.current().getCaller();

        // Verify network id
        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            // see NetworkVO.java
            
            InvalidParameterValueException ex = new InvalidParameterValueException("unable to find network with specified id");
            ex.addProxyObject(network, networkId, "networkId");            
            throw ex;
        }

        // don't allow to delete system network
        if (isNetworkSystem(network)) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Network with specified id is system and can't be removed");
            ex.addProxyObject(network, network.getId(), "networkId");            
            throw ex;
        }

        Account owner = _accountMgr.getAccount(network.getAccountId());

        // Perform permission check
        _accountMgr.checkAccess(caller, null, true, network);

        User callerUser = _accountMgr.getActiveUser(UserContext.current().getCallerUserId());
        ReservationContext context = new ReservationContextImpl(null, null, callerUser, owner);

        return destroyNetwork(networkId, context);
    }

    @Override
    @DB
    public boolean shutdownNetwork(long networkId, ReservationContext context, boolean cleanupElements) {
        boolean result = false;
        
        NetworkVO network = _networksDao.lockRow(networkId, true);
        if (network == null) {
            s_logger.debug("Unable to find network with id: " + networkId);
            return false;
        }
        if (network.getState() != Network.State.Implemented && network.getState() != Network.State.Shutdown) {
            s_logger.debug("Network is not implemented: " + network);
            return false;
        }

        network.setState(Network.State.Shutdown);
        _networksDao.update(network.getId(), network);

        boolean success = shutdownNetworkElementsAndResources(context, cleanupElements, network);

        Transaction txn = Transaction.currentTxn();
        txn.start();
        if (success) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Network id=" + networkId + " is shutdown successfully, cleaning up corresponding resources now.");
            }
            NetworkGuru guru = _networkGurus.get(network.getGuruName());
            NetworkProfile profile = convertNetworkToNetworkProfile(network.getId());
            guru.shutdown(profile, _networkOfferingDao.findById(network.getNetworkOfferingId()));

            applyProfileToNetwork(network, profile);

            network.setState(Network.State.Allocated);
            network.setRestartRequired(false);
            _networksDao.update(network.getId(), network);
            _networksDao.clearCheckForGc(networkId);
            result = true;
        } else {
            network.setState(Network.State.Implemented);
            _networksDao.update(network.getId(), network);
            result = false;
        }
        txn.commit();
        return result;
    }

    private boolean shutdownNetworkElementsAndResources(ReservationContext context, boolean cleanupElements, NetworkVO network) {
        // 1) Cleanup all the rules for the network. If it fails, just log the failure and proceed with shutting down
        // the elements
        boolean cleanupResult = true;
        try {
            cleanupResult = shutdownNetworkResources(network.getId(), context.getAccount(), context.getCaller().getId());
        } catch (Exception ex) {
            s_logger.warn("shutdownNetworkRules failed during the network " + network + " shutdown due to ", ex);
        } finally {
            // just warn the administrator that the network elements failed to shutdown
            if (!cleanupResult) {
                s_logger.warn("Failed to cleanup network id=" + network.getId() + " resources as a part of shutdownNetwork");
            }
        }

        // 2) Shutdown all the network elements
        // get providers to shutdown
        List<Provider> providersToShutdown = getNetworkProviders(network.getId());
        boolean success = true;
        for (NetworkElement element : _networkElements) {
            if (providersToShutdown.contains(element.getProvider())) {
                try {
                    if (!isProviderEnabledInPhysicalNetwork(getPhysicalNetworkId(network), element.getProvider().getName())) {
                        s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName() + " either doesn't exist or not enabled in the physical network "
                                + getPhysicalNetworkId(network));
                        success = false;
                    }
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Sending network shutdown to " + element.getName());
                    }
                    if (!element.shutdown(network, context, cleanupElements)) {
                        s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName());
                        success = false;
                    }
                } catch (ResourceUnavailableException e) {
                    s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName(), e);
                    success = false;
                } catch (ConcurrentOperationException e) {
                    s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName(), e);
                    success = false;
                } catch (Exception e) {
                    s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName(), e);
                    success = false;
                }
            }
        }
        return success;
    }

    @Override
    @DB
    public boolean destroyNetwork(long networkId, ReservationContext context) {
        Account callerAccount = _accountMgr.getAccount(context.getCaller().getAccountId());

        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            s_logger.debug("Unable to find network with id: " + networkId);
            return false;
        }

        // Don't allow to delete network via api call when it has vms assigned to it
        int nicCount = getActiveNicsInNetwork(networkId);
        if (nicCount > 0) {
            s_logger.debug("Unable to remove the network id=" + networkId + " as it has active Nics.");
            return false;
        }

        // Make sure that there are no user vms in the network that are not Expunged/Error
        List<UserVmVO> userVms = _userVmDao.listByNetworkIdAndStates(networkId);

        for (UserVmVO vm : userVms) {
            if (!(vm.getState() == VirtualMachine.State.Expunging && vm.getRemoved() != null)) {
                s_logger.warn("Can't delete the network, not all user vms are expunged. Vm " + vm + " is in " + vm.getState() + " state");
                return false;
            }
        }
        
        //In Basic zone, make sure that there are no non-removed console proxies and SSVMs using the network
        DataCenter zone = _configMgr.getZone(network.getDataCenterId());
        if (zone.getNetworkType() == NetworkType.Basic) {
            List<VMInstanceVO> systemVms = _vmDao.listNonRemovedVmsByTypeAndNetwork(network.getId(), 
                    Type.ConsoleProxy, Type.SecondaryStorageVm);
            if (systemVms != null && !systemVms.isEmpty()) {
                s_logger.warn("Can't delete the network, not all consoleProxy/secondaryStorage vms are expunged");
                return false;
            }
        }

        // Shutdown network first
        shutdownNetwork(networkId, context, false);

        // get updated state for the network
        network = _networksDao.findById(networkId);
        if (network.getState() != Network.State.Allocated && network.getState() != Network.State.Setup) {
            s_logger.debug("Network is not not in the correct state to be destroyed: " + network.getState());
            return false;
        }

        boolean success = true;
        if (!cleanupNetworkResources(networkId, callerAccount, context.getCaller().getId())) {
            s_logger.warn("Unable to delete network id=" + networkId + ": failed to cleanup network resources");
            return false;
        }

        // get providers to destroy
        List<Provider> providersToDestroy = getNetworkProviders(network.getId());
        for (NetworkElement element : _networkElements) {
            if (providersToDestroy.contains(element.getProvider())) {
                try {
                    if (!isProviderEnabledInPhysicalNetwork(getPhysicalNetworkId(network), element.getProvider().getName())) {
                        s_logger.warn("Unable to complete destroy of the network elements due to element: " + element.getName() + " either doesn't exist or not enabled in the physical network "
                                + getPhysicalNetworkId(network));
                        success = false;
                    }

                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Sending destroy to " + element);
                    }

                    if (!element.destroy(network, context)) {
                        success = false;
                        s_logger.warn("Unable to complete destroy of the network: failed to destroy network element " + element.getName());
                    }
                } catch (ResourceUnavailableException e) {
                    s_logger.warn("Unable to complete destroy of the network due to element: " + element.getName(), e);
                    success = false;
                } catch (ConcurrentOperationException e) {
                    s_logger.warn("Unable to complete destroy of the network due to element: " + element.getName(), e);
                    success = false;
                } catch (Exception e) {
                    s_logger.warn("Unable to complete destroy of the network due to element: " + element.getName(), e);
                    success = false;
                }
            }
        }

        if (success) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Network id=" + networkId + " is destroyed successfully, cleaning up corresponding resources now.");
            }
            NetworkGuru guru = _networkGurus.get(network.getGuruName());
            Account owner = _accountMgr.getAccount(network.getAccountId());

            Transaction txn = Transaction.currentTxn();
            txn.start();
            guru.trash(network, _networkOfferingDao.findById(network.getNetworkOfferingId()), owner);

            if (!deleteVlansInNetwork(network.getId(), context.getCaller().getId(), callerAccount)) {
                success = false;
                s_logger.warn("Failed to delete network " + network + "; was unable to cleanup corresponding ip ranges");
            } else {
                // commit transaction only when ips and vlans for the network are released successfully
                network.setState(Network.State.Destroy);
                _networksDao.update(network.getId(), network);
                _networksDao.remove(network.getId());
                
                NetworkOffering ntwkOff = _configMgr.getNetworkOffering(network.getNetworkOfferingId());
                boolean updateResourceCount = resourceCountNeedsUpdate(ntwkOff, network.getAclType());
                if (updateResourceCount) {
                    _resourceLimitMgr.decrementResourceCount(owner.getId(), ResourceType.network);
                }
                txn.commit();
            }
        }

        return success;
    }

    private boolean resourceCountNeedsUpdate(NetworkOffering ntwkOff, ACLType aclType) {
        boolean updateResourceCount = (!ntwkOff.getSpecifyVlan() && aclType == ACLType.Account);
        return updateResourceCount;
    }

    protected boolean deleteVlansInNetwork(long networkId, long userId, Account callerAccount) {
        
        //cleanup Public vlans
        List<VlanVO> publicVlans = _vlanDao.listVlansByNetworkId(networkId);
        boolean result = true;
        for (VlanVO vlan : publicVlans) {
            if (!_configMgr.deleteVlanAndPublicIpRange(_accountMgr.getSystemUser().getId(), vlan.getId(), callerAccount)) {
                s_logger.warn("Failed to delete vlan " + vlan.getId() + ");");
                result = false;
            }
        }      
        
        //cleanup private vlans
        int privateIpAllocCount = _privateIpDao.countAllocatedByNetworkId(networkId);
        if (privateIpAllocCount > 0) {
            s_logger.warn("Can't delete Private ip range for network " + networkId + " as it has allocated ip addresses");
            result = false;
        } else {
            _privateIpDao.deleteByNetworkId(networkId);
            s_logger.debug("Deleted ip range for private network id=" + networkId);
        }
        return result;
    }

    @Override
    public boolean validateRule(FirewallRule rule) {
        Network network = _networksDao.findById(rule.getNetworkId());
        Purpose purpose = rule.getPurpose();
        for (NetworkElement ne : _networkElements) {
            boolean validated;
            switch (purpose) {
            case LoadBalancing:
                if (!(ne instanceof LoadBalancingServiceProvider)) {
                    continue;
                }
                validated = ((LoadBalancingServiceProvider) ne).validateLBRule(network, (LoadBalancingRule) rule);
                if (!validated)
                    return false;
                break;
            default:
                s_logger.debug("Unable to validate network rules for purpose: " + purpose.toString());
                validated = false;
            }
        }
        return true;
    }

    @Override
    /* The rules here is only the same kind of rule, e.g. all load balancing rules or all port forwarding rules */
    public boolean applyRules(List<? extends FirewallRule> rules, boolean continueOnError) throws ResourceUnavailableException {
        if (rules == null || rules.size() == 0) {
            s_logger.debug("There are no rules to forward to the network elements");
            return true;
        }

        boolean success = true;
        Network network = _networksDao.findById(rules.get(0).getNetworkId());
        Purpose purpose = rules.get(0).getPurpose();

        // get the list of public ip's owned by the network
        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        List<PublicIp> publicIps = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            for (IPAddressVO userIp : userIps) {
                PublicIp publicIp = new PublicIp(userIp, _vlanDao.findById(userIp.getVlanId()), NetUtils.createSequenceBasedMacAddress(userIp.getMacAddress()));
                publicIps.add(publicIp);
            }
        }

        // rules can not programmed unless IP is associated with network service provider, so run IP assoication for
        // the network so as to ensure IP is associated before applying rules (in add state)
        applyIpAssociations(network, false, continueOnError, publicIps);

        for (NetworkElement ne : _networkElements) {
            Provider provider = Network.Provider.getProvider(ne.getName());
            if (provider == null) {
                if (ne.getName().equalsIgnoreCase("Ovs") || ne.getName().equalsIgnoreCase("BareMetal")
                        || ne.getName().equalsIgnoreCase("CiscoNexus1000vVSM")) {
                    continue;
                }
                throw new CloudRuntimeException("Unable to identify the provider by name " + ne.getName());
            }
            try {
                boolean handled;
                switch (purpose) {
                case LoadBalancing:
                    boolean isLbProvider = isProviderSupportServiceInNetwork(network.getId(), Service.Lb, provider);
                    if (!(ne instanceof LoadBalancingServiceProvider && isLbProvider)) {
                        continue;
                    }
                    handled = ((LoadBalancingServiceProvider) ne).applyLBRules(network, (List<LoadBalancingRule>) rules);
                    break;
                case PortForwarding:
                    boolean isPfProvider = isProviderSupportServiceInNetwork(network.getId(), Service.PortForwarding, provider);
                    if (!(ne instanceof PortForwardingServiceProvider && isPfProvider)) {
                        continue;
                    }
                    handled = ((PortForwardingServiceProvider) ne).applyPFRules(network, (List<PortForwardingRule>) rules);
                    break;
                case StaticNat:
                    /* It's firewall rule for static nat, not static nat rule */
                    /* Fall through */
                case Firewall:
                    boolean isFirewallProvider = isProviderSupportServiceInNetwork(network.getId(), Service.Firewall, provider);
                    if (!(ne instanceof FirewallServiceProvider && isFirewallProvider)) {
                        continue;
                    }
                    handled = ((FirewallServiceProvider) ne).applyFWRules(network, rules);
                    break;
                case NetworkACL:
                    boolean isNetworkACLProvider = isProviderSupportServiceInNetwork(network.getId(), Service.NetworkACL, provider);
                    if (!(ne instanceof NetworkACLServiceProvider && isNetworkACLProvider)) {
                        continue;
                    }
                    handled = ((NetworkACLServiceProvider) ne).applyNetworkACLs(network, rules);
                    break;
                default:
                    s_logger.debug("Unable to handle network rules for purpose: " + purpose.toString());
                    handled = false;
                }
                s_logger.debug("Network Rules for network " + network.getId() + " were " + (handled ? "" : " not") + " handled by " + ne.getName());
            } catch (ResourceUnavailableException e) {
                if (!continueOnError) {
                    throw e;
                }
                s_logger.warn("Problems with " + ne.getName() + " but pushing on", e);
                success = false;
            }
        }

        // if all the rules configured on public IP are revoked then dis-associate IP with network service provider
        applyIpAssociations(network, true, continueOnError, publicIps);

        return success;
    }

    public class NetworkGarbageCollector implements Runnable {

        @Override
        public void run() {
            try {
                List<Long> shutdownList = new ArrayList<Long>();
                long currentTime = System.currentTimeMillis() >> 10;
                HashMap<Long, Long> stillFree = new HashMap<Long, Long>();

                List<Long> networkIds = _networksDao.findNetworksToGarbageCollect();
                for (Long networkId : networkIds) {
                    Long time = _lastNetworkIdsToFree.remove(networkId);
                    if (time == null) {
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("We found network " + networkId + " to be free for the first time.  Adding it to the list: " + currentTime);
                        }
                        stillFree.put(networkId, currentTime);
                    } else if (time > (currentTime - _networkGcWait)) {
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("Network " + networkId + " is still free but it's not time to shutdown yet: " + time);
                        }
                        stillFree.put(networkId, time);
                    } else {
                        shutdownList.add(networkId);
                    }
                }

                _lastNetworkIdsToFree = stillFree;

                for (Long networkId : shutdownList) {

                    // If network is removed, unset gc flag for it
                    if (getNetwork(networkId) == null) {
                        s_logger.debug("Network id=" + networkId + " is removed, so clearing up corresponding gc check");
                        _networksDao.clearCheckForGc(networkId);
                    } else {
                        try {

                            User caller = _accountMgr.getSystemUser();
                            Account owner = _accountMgr.getAccount(getNetwork(networkId).getAccountId());

                            ReservationContext context = new ReservationContextImpl(null, null, caller, owner);

                            shutdownNetwork(networkId, context, false);
                        } catch (Exception e) {
                            s_logger.warn("Unable to shutdown network: " + networkId);
                        }
                    }
                }
            } catch (Exception e) {
                s_logger.warn("Caught exception while running network gc: ", e);
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_RESTART, eventDescription = "restarting network", async = true)
    public boolean restartNetwork(RestartNetworkCmd cmd, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        // This method restarts all network elements belonging to the network and re-applies all the rules
        Long networkId = cmd.getNetworkId();

        User callerUser = _accountMgr.getActiveUser(UserContext.current().getCallerUserId());
        Account callerAccount = _accountMgr.getActiveAccountById(callerUser.getAccountId());

        // Check if network exists
        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {            
            InvalidParameterValueException ex = new InvalidParameterValueException("Network with specified id doesn't exist");
            ex.addProxyObject("networks", networkId, "networkId");
            throw ex;
        }

        // Don't allow to restart network if it's not in Implemented/Setup state
        if (!(network.getState() == Network.State.Implemented || network.getState() == Network.State.Setup)) {
            throw new InvalidParameterValueException("Network is not in the right state to be restarted. Correct states are: " + Network.State.Implemented + ", " + Network.State.Setup);
        }
        
        if (network.getBroadcastDomainType() == BroadcastDomainType.Lswitch ) {
        	/** 
        	 * Unable to restart these networks now.
        	 * TODO Restarting a SDN based network requires updating the nics and the configuration
        	 * in the controller. This requires a non-trivial rewrite of the restart procedure.
        	 */
        	throw new InvalidParameterException("Unable to restart a running SDN network.");
        }

        _accountMgr.checkAccess(callerAccount, null, true, network);

        boolean success = restartNetwork(networkId, callerAccount, callerUser, cleanup);

        if (success) {
            s_logger.debug("Network id=" + networkId + " is restarted successfully.");
        } else {
            s_logger.warn("Network id=" + networkId + " failed to restart.");
        }

        return success;
    }

    @Override
    public boolean startNetwork(long networkId, DeployDestination dest, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        // Check if network exists
        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Network with specified id doesn't exist");
            ex.addProxyObject(network, networkId, "networkId");            
            throw ex;
        }

        // implement the network
        s_logger.debug("Starting network " + network + "...");
        Pair<NetworkGuru, NetworkVO> implementedNetwork = implementNetwork(networkId, dest, context);
        if (implementedNetwork.first() == null) {
            s_logger.warn("Failed to start the network " + network);
            return false;
        } else {
            return true;
        }
    }

    private boolean restartNetwork(long networkId, Account callerAccount, User callerUser, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        NetworkVO network = _networksDao.findById(networkId);

        s_logger.debug("Restarting network " + networkId + "...");

        ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);

        if (cleanup) {
            // shutdown the network
            s_logger.debug("Shutting down the network id=" + networkId + " as a part of network restart");

            if (!shutdownNetworkElementsAndResources(context, true, network)) {
                s_logger.debug("Failed to shutdown the network elements and resources as a part of network restart: " + network.getState());
                setRestartRequired(network, true);
                return false;
            }
        } else {
            s_logger.debug("Skip the shutting down of network id=" + networkId);
        }

        // implement the network elements and rules again
        DeployDestination dest = new DeployDestination(_dcDao.findById(network.getDataCenterId()), null, null, null);

        s_logger.debug("Implementing the network " + network + " elements and resources as a part of network restart");
        NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());

        try {
            implementNetworkElementsAndResources(dest, context, network, offering);
            setRestartRequired(network, true);
        } catch (Exception ex) {
            s_logger.warn("Failed to implement network " + network + " elements and resources as a part of network restart due to ", ex);
            return false;
        }
        setRestartRequired(network, false);
        return true;
    }

    private void setRestartRequired(NetworkVO network, boolean restartRequired) {
        s_logger.debug("Marking network " + network + " with restartRequired=" + restartRequired);
        network.setRestartRequired(restartRequired);
        _networksDao.update(network.getId(), network);
    }

    // This method re-programs the rules/ips for existing network
    protected boolean reprogramNetworkRules(long networkId, Account caller, NetworkVO network) throws ResourceUnavailableException {
        boolean success = true;
        // associate all ip addresses
        if (!applyIpAssociations(network, false)) {
            s_logger.warn("Failed to apply ip addresses as a part of network id" + networkId + " restart");
            success = false;
        }

        // apply static nat
        if (!_rulesMgr.applyStaticNatsForNetwork(networkId, false, caller)) {
            s_logger.warn("Failed to apply static nats a part of network id" + networkId + " restart");
            success = false;
        }

        // apply firewall rules
        List<FirewallRuleVO> firewallRulesToApply = _firewallDao.listByNetworkAndPurpose(networkId, Purpose.Firewall);
        if (!_firewallMgr.applyFirewallRules(firewallRulesToApply, false, caller)) {
            s_logger.warn("Failed to reapply firewall rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply port forwarding rules
        if (!_rulesMgr.applyPortForwardingRulesForNetwork(networkId, false, caller)) {
            s_logger.warn("Failed to reapply port forwarding rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply static nat rules
        if (!_rulesMgr.applyStaticNatRulesForNetwork(networkId, false, caller)) {
            s_logger.warn("Failed to reapply static nat rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply load balancer rules
        if (!_lbMgr.applyLoadBalancersForNetwork(networkId)) {
            s_logger.warn("Failed to reapply load balancer rules as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply vpn rules
        List<? extends RemoteAccessVpn> vpnsToReapply = _vpnMgr.listRemoteAccessVpns(networkId);
        if (vpnsToReapply != null) {
            for (RemoteAccessVpn vpn : vpnsToReapply) {
                // Start remote access vpn per ip
                if (_vpnMgr.startRemoteAccessVpn(vpn.getServerAddressId(), false) == null) {
                    s_logger.warn("Failed to reapply vpn rules as a part of network id=" + networkId + " restart");
                    success = false;
                }
            }
        }
        
        //apply network ACLs
        if (!_networkACLMgr.applyNetworkACLs(networkId, caller)) {
            s_logger.warn("Failed to reapply network ACLs as a part of  of network id=" + networkId + " restart");
            success = false;
        }
        
        return success;
    }

    @Override
    public int getActiveNicsInNetwork(long networkId) {
        return _networksDao.getActiveNicsIn(networkId);
    }

    @Override
    public Map<Service, Map<Capability, String>> getNetworkCapabilities(long networkId) {

        Map<Service, Map<Capability, String>> networkCapabilities = new HashMap<Service, Map<Capability, String>>();

        // list all services of this networkOffering
        List<NetworkServiceMapVO> servicesMap = _ntwkSrvcDao.getServicesInNetwork(networkId);
        for (NetworkServiceMapVO instance : servicesMap) {
            Service service = Service.getService(instance.getService());
            NetworkElement element = getElementImplementingProvider(instance.getProvider());
            if (element != null) {
                Map<Service, Map<Capability, String>> elementCapabilities = element.getCapabilities();
                ;
                if (elementCapabilities != null) {
                    networkCapabilities.put(service, elementCapabilities.get(service));
                }
            }
        }

        return networkCapabilities;
    }

    @Override
    public Map<Capability, String> getNetworkServiceCapabilities(long networkId, Service service) {

        if (!areServicesSupportedInNetwork(networkId, service)) {
            // TBD: networkId to uuid. No VO object being passed. So we will need to call
            // addProxyObject with hardcoded tablename. Or we should probably look up the correct dao proxy object.
            throw new UnsupportedServiceException("Service " + service.getName() + " is not supported in the network id=" + networkId);
        }

        Map<Capability, String> serviceCapabilities = new HashMap<Capability, String>();

        // get the Provider for this Service for this offering
        String provider = _ntwkSrvcDao.getProviderForServiceInNetwork(networkId, service);

        NetworkElement element = getElementImplementingProvider(provider);
        if (element != null) {
            Map<Service, Map<Capability, String>> elementCapabilities = element.getCapabilities();
            ;

            if (elementCapabilities == null || !elementCapabilities.containsKey(service)) {
                throw new UnsupportedServiceException("Service " + service.getName() + " is not supported by the element=" + element.getName() + " implementing Provider=" + provider);
            }
            serviceCapabilities = elementCapabilities.get(service);
        }

        return serviceCapabilities;
    }

    @Override
    public Map<Capability, String> getNetworkOfferingServiceCapabilities(NetworkOffering offering, Service service) {

        if (!areServicesSupportedByNetworkOffering(offering.getId(), service)) {
            // TBD: We should be sending networkOfferingId and not the offering object itself.  
            throw new UnsupportedServiceException("Service " + service.getName() + " is not supported by the network offering " + offering);
        }

        Map<Capability, String> serviceCapabilities = new HashMap<Capability, String>();

        // get the Provider for this Service for this offering
        List<String> providers = _ntwkOfferingSrvcDao.listProvidersForServiceForNetworkOffering(offering.getId(), service);
        if (providers.isEmpty()) {
            // TBD: We should be sending networkOfferingId and not the offering object itself.
            throw new InvalidParameterValueException("Service " + service.getName() + " is not supported by the network offering " + offering);
        }

        // FIXME - in post 3.0 we are going to support multiple providers for the same service per network offering, so
        // we have to calculate capabilities for all of them
        String provider = providers.get(0);

        // FIXME we return the capabilities of the first provider of the service - what if we have multiple providers
        // for same Service?
        NetworkElement element = getElementImplementingProvider(provider);
        if (element != null) {
            Map<Service, Map<Capability, String>> elementCapabilities = element.getCapabilities();
            ;

            if (elementCapabilities == null || !elementCapabilities.containsKey(service)) {
                // TBD: We should be sending providerId and not the offering object itself.
                throw new UnsupportedServiceException("Service " + service.getName() + " is not supported by the element=" + element.getName() + " implementing Provider=" + provider);
            }
            serviceCapabilities = elementCapabilities.get(service);
        }

        return serviceCapabilities;
    }

    @Override
    public NetworkVO getSystemNetworkByZoneAndTrafficType(long zoneId, TrafficType trafficType) {
        // find system public network offering
        Long networkOfferingId = null;
        List<NetworkOfferingVO> offerings = _networkOfferingDao.listSystemNetworkOfferings();
        for (NetworkOfferingVO offering : offerings) {
            if (offering.getTrafficType() == trafficType) {
                networkOfferingId = offering.getId();
                break;
            }
        }

        if (networkOfferingId == null) {
            throw new InvalidParameterValueException("Unable to find system network offering with traffic type " + trafficType);
        }

        List<NetworkVO> networks = _networksDao.listBy(Account.ACCOUNT_ID_SYSTEM, networkOfferingId, zoneId);
        if (networks == null || networks.isEmpty()) {
            // TBD: send uuid instead of zoneId. Hardcode tablename in call to addProxyObject(). 
            throw new InvalidParameterValueException("Unable to find network with traffic type " + trafficType + " in zone " + zoneId);
        }
        return networks.get(0);
    }

    @Override
    public NetworkVO getNetworkWithSecurityGroupEnabled(Long zoneId) {
        List<NetworkVO> networks = _networksDao.listByZoneSecurityGroup(zoneId);
        if (networks == null || networks.isEmpty()) {
            return null;
        }

        if (networks.size() > 1) {
            s_logger.debug("There are multiple network with security group enabled? select one of them...");
        }
        return networks.get(0);
    }

    @Override
    public PublicIpAddress getPublicIpAddress(long ipAddressId) {
        IPAddressVO addr = _ipAddressDao.findById(ipAddressId);
        if (addr == null) {
            return null;
        }

        return new PublicIp(addr, _vlanDao.findById(addr.getVlanId()), NetUtils.createSequenceBasedMacAddress(addr.getMacAddress()));
    }

    @Override
    public List<VlanVO> listPodVlans(long podId) {
        List<VlanVO> vlans = _vlanDao.listVlansForPodByType(podId, VlanType.DirectAttached);
        return vlans;
    }

    @Override
    public List<NetworkVO> listNetworksUsedByVm(long vmId, boolean isSystem) {
        List<NetworkVO> networks = new ArrayList<NetworkVO>();

        List<NicVO> nics = _nicDao.listByVmId(vmId);
        if (nics != null) {
            for (Nic nic : nics) {
                NetworkVO network = _networksDao.findByIdIncludingRemoved(nic.getNetworkId());

                if (isNetworkSystem(network) == isSystem) {
                    networks.add(network);
                }
            }
        }

        return networks;
    }

    @Override
    public Nic getNicInNetwork(long vmId, long networkId) {
        return _nicDao.findByInstanceIdAndNetworkId(networkId, vmId);
    }

    @Override
    public String getIpInNetwork(long vmId, long networkId) {
        Nic guestNic = getNicInNetwork(vmId, networkId);
        assert (guestNic != null && guestNic.getIp4Address() != null) : "Vm doesn't belong to network associated with " +
                "ipAddress or ip4 address is null";
        return guestNic.getIp4Address();
    }

    @Override
    public String getIpInNetworkIncludingRemoved(long vmId, long networkId) {
        Nic guestNic = getNicInNetworkIncludingRemoved(vmId, networkId);
        assert (guestNic != null && guestNic.getIp4Address() != null) : "Vm doesn't belong to network associated with " +
                "ipAddress or ip4 address is null";
        return guestNic.getIp4Address();
    }

    private Nic getNicInNetworkIncludingRemoved(long vmId, long networkId) {
        return _nicDao.findByInstanceIdAndNetworkIdIncludingRemoved(networkId, vmId);
    }

    @Override
    @DB
    public boolean associateIpAddressListToAccount(long userId, long accountId, long zoneId, Long vlanId, Network guestNetwork)
            throws InsufficientCapacityException, ConcurrentOperationException,
            ResourceUnavailableException, ResourceAllocationException {
        Account owner = _accountMgr.getActiveAccountById(accountId);
        boolean createNetwork = false;
        
        if (guestNetwork != null && guestNetwork.getTrafficType() != TrafficType.Guest) {
            throw new InvalidParameterValueException("Network " + guestNetwork + " is not of a type " + TrafficType.Guest);
        }

        Transaction txn = Transaction.currentTxn();
        txn.start();

        if (guestNetwork == null) {
            List<? extends Network> networks = getIsolatedNetworksWithSourceNATOwnedByAccountInZone(zoneId, owner);
            if (networks.size() == 0) {
                createNetwork = true;
            } else if (networks.size() == 1)  {
                guestNetwork = networks.get(0);
            } else {
                throw new InvalidParameterValueException("Error, more than 1 Guest Isolated Networks with SourceNAT " +
                        "service enabled found for this account, cannot assosiate the IP range, please provide the network ID");
            }
        }

        // create new Virtual network (Isolated with SourceNAT) for the user if it doesn't exist
        if (createNetwork) {
            List<NetworkOfferingVO> requiredOfferings = _networkOfferingDao.listByAvailability(Availability.Required, false);
            if (requiredOfferings.size() < 1) {
                throw new CloudRuntimeException("Unable to find network offering with availability=" + 
            Availability.Required + " to automatically create the network as part of createVlanIpRange");
            }
            if (requiredOfferings.get(0).getState() == NetworkOffering.State.Enabled) {
                
                long physicalNetworkId = findPhysicalNetworkId(zoneId, requiredOfferings.get(0).getTags(), requiredOfferings.get(0).getTrafficType());
                // Validate physical network
                PhysicalNetwork physicalNetwork = _physicalNetworkDao.findById(physicalNetworkId);
                if (physicalNetwork == null) {
                    throw new InvalidParameterValueException("Unable to find physical network with id: "+physicalNetworkId   + " and tag: " +requiredOfferings.get(0).getTags());
                }
                
                s_logger.debug("Creating network for account " + owner + " from the network offering id=" + 
            requiredOfferings.get(0).getId() + " as a part of createVlanIpRange process");
                guestNetwork = createGuestNetwork(requiredOfferings.get(0).getId(), owner.getAccountName() + "-network"
                        , owner.getAccountName() + "-network", null, null, null, null, owner, null, physicalNetwork, 
                        zoneId, ACLType.Account,
                        null, null);
                if (guestNetwork == null) {
                    s_logger.warn("Failed to create default Virtual network for the account " + accountId + "in zone " + zoneId);
                    throw new CloudRuntimeException("Failed to create a Guest Isolated Networks with SourceNAT " +
                            "service enabled as a part of createVlanIpRange, for the account " + accountId + "in zone " + zoneId);
                }
            } else {
                throw new CloudRuntimeException("Required network offering id=" + requiredOfferings.get(0).getId() 
                        + " is not in " + NetworkOffering.State.Enabled); 
            }
        }

        // Check if there is a source nat ip address for this account; if not - we have to allocate one
        boolean allocateSourceNat = false;
        List<IPAddressVO> sourceNat = _ipAddressDao.listByAssociatedNetwork(guestNetwork.getId(), true);
        if (sourceNat.isEmpty()) {
            allocateSourceNat = true;
        }

        // update all ips with a network id, mark them as allocated and update resourceCount/usage
        List<IPAddressVO> ips = _ipAddressDao.listByVlanId(vlanId);
        boolean isSourceNatAllocated = false;
        for (IPAddressVO addr : ips) {
            if (addr.getState() != State.Allocated) {
                if (!isSourceNatAllocated && allocateSourceNat) {
                    addr.setSourceNat(true);
                    isSourceNatAllocated = true;
                } else {
                    addr.setSourceNat(false);
                }
                addr.setAssociatedWithNetworkId(guestNetwork.getId());
                addr.setVpcId(guestNetwork.getVpcId());
                addr.setAllocatedTime(new Date());
                addr.setAllocatedInDomainId(owner.getDomainId());
                addr.setAllocatedToAccountId(owner.getId());
                addr.setSystem(false);
                addr.setState(IpAddress.State.Allocating);
                markPublicIpAsAllocated(addr);
            }
        }

        txn.commit();
        return true;
    }

    @Override
    public List<NicVO> getNicsForTraffic(long vmId, TrafficType type) {
        SearchCriteria<NicVO> sc = NicForTrafficTypeSearch.create();
        sc.setParameters("instance", vmId);
        sc.setJoinParameters("network", "traffictype", type);

        return _nicDao.search(sc, null);
    }

    @Override
    public IpAddress getIp(long ipAddressId) {
        return _ipAddressDao.findById(ipAddressId);
    }

    @Override
    public NetworkProfile convertNetworkToNetworkProfile(long networkId) {
        NetworkVO network = _networksDao.findById(networkId);
        NetworkGuru guru = _networkGurus.get(network.getGuruName());
        NetworkProfile profile = new NetworkProfile(network);
        guru.updateNetworkProfile(profile);

        return profile;
    }

    @Override
    public Network getDefaultNetworkForVm(long vmId) {
        Nic defaultNic = getDefaultNic(vmId);
        if (defaultNic == null) {
            return null;
        } else {
            return _networksDao.findById(defaultNic.getNetworkId());
        }
    }

    @Override
    public Nic getDefaultNic(long vmId) {
        List<NicVO> nics = _nicDao.listByVmId(vmId);
        Nic defaultNic = null;
        if (nics != null) {
            for (Nic nic : nics) {
                if (nic.isDefaultNic()) {
                    defaultNic = nic;
                    break;
                }
            }
        } else {
            s_logger.debug("Unable to find default network for the vm; vm doesn't have any nics");
            return null;
        }

        if (defaultNic == null) {
            s_logger.debug("Unable to find default network for the vm; vm doesn't have default nic");
        }

        return defaultNic;

    }

    @Override
    public UserDataServiceProvider getPasswordResetProvider(Network network) {
        String passwordProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.UserData);

        if (passwordProvider == null) {
            s_logger.debug("Network " + network + " doesn't support service " + Service.UserData.getName());
            return null;
        }
        
        return (UserDataServiceProvider)getElementImplementingProvider(passwordProvider);
    }

    @Override
    public UserDataServiceProvider getUserDataUpdateProvider(Network network) {
        String userDataProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.UserData);

        if (userDataProvider == null) {
            s_logger.debug("Network " + network + " doesn't support service " + Service.UserData.getName());
            return null;
        }

        return (UserDataServiceProvider)getElementImplementingProvider(userDataProvider);
    }

    @Override
    public boolean networkIsConfiguredForExternalNetworking(long zoneId, long networkId) {
        boolean netscalerInNetwork = isProviderForNetwork(Network.Provider.Netscaler, networkId);
        boolean juniperInNetwork = isProviderForNetwork(Network.Provider.JuniperSRX, networkId);
        boolean f5InNetwork = isProviderForNetwork(Network.Provider.F5BigIp, networkId);

        if (netscalerInNetwork || juniperInNetwork || f5InNetwork) {
            return true;
        } else {
            return false;
        }
    }

    public boolean networkOfferingIsConfiguredForExternalNetworking(long networkOfferingId) {
        boolean netscalerInNetworkOffering = isProviderForNetworkOffering(Network.Provider.Netscaler, networkOfferingId);
        boolean juniperInNetworkOffering = isProviderForNetworkOffering(Network.Provider.JuniperSRX, networkOfferingId);
        boolean f5InNetworkOffering = isProviderForNetworkOffering(Network.Provider.F5BigIp, networkOfferingId);

        if (netscalerInNetworkOffering || juniperInNetworkOffering || f5InNetworkOffering) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean areServicesSupportedByNetworkOffering(long networkOfferingId, Service... services) {
        return (_ntwkOfferingSrvcDao.areServicesSupportedByNetworkOffering(networkOfferingId, services));
    }

    @Override
    public boolean areServicesSupportedInNetwork(long networkId, Service... services) {
        return (_ntwkSrvcDao.areServicesSupportedInNetwork(networkId, services));
    }

    private boolean cleanupIpResources(long ipId, long userId, Account caller) {
        boolean success = true;

        // Revoke all firewall rules for the ip
        try {
            s_logger.debug("Revoking all " + Purpose.Firewall + "rules as a part of public IP id=" + ipId + " release...");
            if (!_firewallMgr.revokeFirewallRulesForIp(ipId, userId, caller)) {
                s_logger.warn("Unable to revoke all the firewall rules for ip id=" + ipId + " as a part of ip release");
                success = false;
            }
        } catch (ResourceUnavailableException e) {
            s_logger.warn("Unable to revoke all firewall rules for ip id=" + ipId + " as a part of ip release", e);
            success = false;
        }

        // Revoke all PF/Static nat rules for the ip
        try {
            s_logger.debug("Revoking all " + Purpose.PortForwarding + "/" + Purpose.StaticNat + " rules as a part of public IP id=" + ipId + " release...");
            if (!_rulesMgr.revokeAllPFAndStaticNatRulesForIp(ipId, userId, caller)) {
                s_logger.warn("Unable to revoke all the port forwarding rules for ip id=" + ipId + " as a part of ip release");
                success = false;
            }
        } catch (ResourceUnavailableException e) {
            s_logger.warn("Unable to revoke all the port forwarding rules for ip id=" + ipId + " as a part of ip release", e);
            success = false;
        }

        s_logger.debug("Revoking all " + Purpose.LoadBalancing + " rules as a part of public IP id=" + ipId + " release...");
        if (!_lbMgr.removeAllLoadBalanacersForIp(ipId, caller, userId)) {
            s_logger.warn("Unable to revoke all the load balancer rules for ip id=" + ipId + " as a part of ip release");
            success = false;
        }

        // remote access vpn can be enabled only for static nat ip, so this part should never be executed under normal
        // conditions
        // only when ip address failed to be cleaned up as a part of account destroy and was marked as Releasing, this part of
        // the code would be triggered
        s_logger.debug("Cleaning up remote access vpns as a part of public IP id=" + ipId + " release...");
        try {
            _vpnMgr.destroyRemoteAccessVpn(ipId, caller);
        } catch (ResourceUnavailableException e) {
            s_logger.warn("Unable to destroy remote access vpn for ip id=" + ipId + " as a part of ip release", e);
            success = false;
        }

        return success;
    }

    @Override
    public String getIpOfNetworkElementInVirtualNetwork(long accountId, long dataCenterId) {

        List<NetworkVO> virtualNetworks = _networksDao.listByZoneAndGuestType(accountId, dataCenterId, Network.GuestType.Isolated, false);

        if (virtualNetworks.isEmpty()) {
            s_logger.trace("Unable to find default Virtual network account id=" + accountId);
            return null;
        }

        NetworkVO virtualNetwork = virtualNetworks.get(0);

        NicVO networkElementNic = _nicDao.findByNetworkIdAndType(virtualNetwork.getId(), Type.DomainRouter);

        if (networkElementNic != null) {
            return networkElementNic.getIp4Address();
        } else {
            s_logger.warn("Unable to set find network element for the network id=" + virtualNetwork.getId());
            return null;
        }
    }

    @Override
    public List<NetworkVO> listNetworksForAccount(long accountId, long zoneId, Network.GuestType type) {
        List<NetworkVO> accountNetworks = new ArrayList<NetworkVO>();
        List<NetworkVO> zoneNetworks = _networksDao.listByZone(zoneId);

        for (NetworkVO network : zoneNetworks) {
            if (!isNetworkSystem(network)) {
                if (network.getGuestType() == Network.GuestType.Shared || !_networksDao.listBy(accountId, network.getId()).isEmpty()) {
                    if (type == null || type == network.getGuestType()) {
                        accountNetworks.add(network);
                    }
                }
            }
        }
        return accountNetworks;
    }

    @Override
    public List<NetworkVO> listAllNetworksInAllZonesByType(Network.GuestType type) {
        List<NetworkVO> networks = new ArrayList<NetworkVO>();
        for (NetworkVO network: _networksDao.listAll()) {
            if (!isNetworkSystem(network)) {
                networks.add(network);
            }
        }
        return networks;
    }
        
    @DB
    @Override
    public IPAddressVO markIpAsUnavailable(long addrId) {
        Transaction txn = Transaction.currentTxn();

        IPAddressVO ip = _ipAddressDao.findById(addrId);

        if (ip.getAllocatedToAccountId() == null && ip.getAllocatedTime() == null) {
            s_logger.trace("Ip address id=" + addrId + " is already released");
            return ip;
        }

        if (ip.getState() != State.Releasing) {
            txn.start();

            // don't decrement resource count for direct ips
            if (ip.getAssociatedWithNetworkId() != null) {
                _resourceLimitMgr.decrementResourceCount(_ipAddressDao.findById(addrId).getAllocatedToAccountId(), ResourceType.public_ip);
            }

            // Save usage event
            if (ip.getAllocatedToAccountId() != Account.ACCOUNT_ID_SYSTEM) {
                VlanVO vlan = _vlanDao.findById(ip.getVlanId());

                String guestType = vlan.getVlanType().toString();

                UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_NET_IP_RELEASE,
                        ip.getAllocatedToAccountId(), ip.getDataCenterId(), addrId, ip.getAddress().addr(), 
                        ip.isSourceNat(), guestType, ip.getSystem());
                _usageEventDao.persist(usageEvent);
            }

            ip = _ipAddressDao.markAsUnavailable(addrId);

            txn.commit();
        }

        return ip;
    }

    @Override
    public boolean isNetworkAvailableInDomain(long networkId, long domainId) {
        Long networkDomainId = null;
        Network network = getNetwork(networkId);
        if (network.getGuestType() != Network.GuestType.Shared) {
            s_logger.trace("Network id=" + networkId + " is not shared");
            return false;
        }

        NetworkDomainVO networkDomainMap = _networkDomainDao.getDomainNetworkMapByNetworkId(networkId);
        if (networkDomainMap == null) {
            s_logger.trace("Network id=" + networkId + " is shared, but not domain specific");
            return true;
        } else {
            networkDomainId = networkDomainMap.getDomainId();
        }

        if (domainId == networkDomainId.longValue()) {
            return true;
        }

        if (networkDomainMap.subdomainAccess) {
            Set<Long> parentDomains = _domainMgr.getDomainParentIds(domainId);

            if (parentDomains.contains(domainId)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public Long getDedicatedNetworkDomain(long networkId) {
        NetworkDomainVO networkMaps = _networkDomainDao.getDomainNetworkMapByNetworkId(networkId);
        if (networkMaps != null) {
            return networkMaps.getDomainId();
        } else {
            return null;
        }
    }

    private boolean checkForNonStoppedVmInNetwork(long networkId) {
        List<UserVmVO> vms = _userVmDao.listByNetworkIdAndStates(networkId, VirtualMachine.State.Starting, 
                VirtualMachine.State.Running, VirtualMachine.State.Migrating, VirtualMachine.State.Stopping);
        return vms.isEmpty();
    }
    
    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_UPDATE, eventDescription = "updating network", async = true)
    public Network updateGuestNetwork(long networkId, String name, String displayText, Account callerAccount, 
            User callerUser, String domainSuffix, Long networkOfferingId, Boolean changeCidr) {
        boolean restartNetwork = false;

        // verify input parameters
        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            // see NetworkVO.java
            InvalidParameterValueException ex = new InvalidParameterValueException("Specified network id doesn't exist in the system");
            ex.addProxyObject("networks", networkId, "networkId");
            throw ex;
        }

        // don't allow to update network in Destroy state
        if (network.getState() == Network.State.Destroy) {
            throw new InvalidParameterValueException("Don't allow to update network in state " + Network.State.Destroy);
        }

        // Don't allow to update system network
        NetworkOffering offering = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (offering.isSystemOnly()) {
            throw new InvalidParameterValueException("Can't update system networks");
        }

        // allow to upgrade only Guest networks
        if (network.getTrafficType() != Networks.TrafficType.Guest) {
            throw new InvalidParameterValueException("Can't allow networks which traffic type is not " + TrafficType.Guest);
        }
        
        _accountMgr.checkAccess(callerAccount, null, true, network);

        if (name != null) {
            network.setName(name);
        }

        if (displayText != null) {
            network.setDisplayText(displayText);
        }

        // network offering and domain suffix can be updated for Isolated networks only in 3.0
        if ((networkOfferingId != null || domainSuffix != null) && network.getGuestType() != GuestType.Isolated) {
            throw new InvalidParameterValueException("NetworkOffering and domain suffix upgrade can be perfomed for Isolated networks only");
        }

        boolean networkOfferingChanged = false;

        long oldNetworkOfferingId = network.getNetworkOfferingId();
        if (networkOfferingId != null) {

            NetworkOfferingVO networkOffering = _networkOfferingDao.findById(networkOfferingId);
            if (networkOffering == null || networkOffering.isSystemOnly()) {
                InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find network offering with specified id");
                ex.addProxyObject(networkOffering, networkOfferingId, "networkOfferingId");                
                throw ex;
            }
            
            // network offering should be in Enabled state
            if (networkOffering.getState() != NetworkOffering.State.Enabled) {
                InvalidParameterValueException ex = new InvalidParameterValueException("Network offering with specified id is not in " + NetworkOffering.State.Enabled + " state, can't upgrade to it");
                ex.addProxyObject(networkOffering, networkOfferingId, "networkOfferingId");                
                throw ex;
            }
            
            
            //can't update from vpc to non-vpc network offering
            boolean forVpcNew = _configMgr.isOfferingForVpc(networkOffering);
            boolean vorVpcOriginal = _configMgr.isOfferingForVpc(_configMgr.getNetworkOffering(oldNetworkOfferingId));
            if (forVpcNew != vorVpcOriginal) {
                String errMsg = forVpcNew ? "a vpc offering " : "not a vpc offering";
                throw new InvalidParameterValueException("Can't update as the new offering is " + errMsg);
            }

            if (networkOfferingId != oldNetworkOfferingId) {
                if (networkOfferingIsConfiguredForExternalNetworking(networkOfferingId) != networkOfferingIsConfiguredForExternalNetworking(oldNetworkOfferingId)
                        && !changeCidr) {
                    throw new InvalidParameterValueException("Updating network failed since guest CIDR needs to be changed!");
                }
                if (changeCidr) {
                    if (!checkForNonStoppedVmInNetwork(network.getId())) {
                        InvalidParameterValueException ex = new InvalidParameterValueException("All user vm of network of specified id should be stopped before changing CIDR!");
                        ex.addProxyObject(network, networkId, "networkId");                       
                        throw ex;
                    }
                }
                // check if the network is upgradable
                if (!canUpgrade(network, oldNetworkOfferingId, networkOfferingId)) {
                    throw new InvalidParameterValueException("Can't upgrade from network offering " + oldNetworkOfferingId + " to " + networkOfferingId + "; check logs for more information");
                }
                restartNetwork = true;
                networkOfferingChanged = true;
            }
        }
        Map<String, String> newSvcProviders = new HashMap<String, String>();
        if (networkOfferingChanged) {
            newSvcProviders = finalizeServicesAndProvidersForNetwork(_configMgr.getNetworkOffering(networkOfferingId), network.getPhysicalNetworkId());
        }

        // don't allow to modify network domain if the service is not supported
        if (domainSuffix != null) {
            // validate network domain
            if (!NetUtils.verifyDomainName(domainSuffix)) {
                throw new InvalidParameterValueException(
                        "Invalid network domain. Total length shouldn't exceed 190 chars. Each domain label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
            }

            long offeringId = oldNetworkOfferingId;
            if (networkOfferingId != null) {
                offeringId = networkOfferingId;
            }

            Map<Network.Capability, String> dnsCapabilities = getNetworkOfferingServiceCapabilities(_configMgr.getNetworkOffering(offeringId), Service.Dns);
            String isUpdateDnsSupported = dnsCapabilities.get(Capability.AllowDnsSuffixModification);
            if (isUpdateDnsSupported == null || !Boolean.valueOf(isUpdateDnsSupported)) {
                // TBD: use uuid instead of networkOfferingId. May need to hardcode tablename in call to addProxyObject().
                throw new InvalidParameterValueException("Domain name change is not supported by the network offering id=" + networkOfferingId);
            }

            network.setNetworkDomain(domainSuffix);
            // have to restart the network
            restartNetwork = true;
        }

        ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);
        // 1) Shutdown all the elements and cleanup all the rules. Don't allow to shutdown network in intermediate
        // states - Shutdown and Implementing
        boolean validStateToShutdown = (network.getState() == Network.State.Implemented || network.getState() == Network.State.Setup || network.getState() == Network.State.Allocated);
        if (restartNetwork) {
            if (validStateToShutdown) {
                if (!changeCidr) {
                    s_logger.debug("Shutting down elements and resources for network id=" + networkId + " as a part of network update");

                    if (!shutdownNetworkElementsAndResources(context, true, network)) {
                        s_logger.warn("Failed to shutdown the network elements and resources as a part of network restart: " + network);
                        CloudRuntimeException ex = new CloudRuntimeException("Failed to shutdown the network elements and resources as a part of update to network of specified id");
                        ex.addProxyObject(network, networkId, "networkId");                       
                        throw ex;
                    }
                } else {
                    // We need to shutdown the network, since we want to re-implement the network.
                    s_logger.debug("Shutting down network id=" + networkId + " as a part of network update");

                    if (!shutdownNetwork(network.getId(), context, true)) {
                        s_logger.warn("Failed to shutdown the network as a part of update to network with specified id");
                        CloudRuntimeException ex = new CloudRuntimeException("Failed to shutdown the network as a part of update of specified network id");
                        ex.addProxyObject(network, networkId, "networkId");                        
                        throw ex;
                    }
                }
            } else {
                CloudRuntimeException ex = new CloudRuntimeException("Failed to shutdown the network elements and resources as a part of update to network with specified id; network is in wrong state: " + network.getState());
                ex.addProxyObject(network, networkId, "networkId");                
                throw ex;
            }
        }

        // 2) Only after all the elements and rules are shutdown properly, update the network VO
        // get updated network
        Network.State networkState = _networksDao.findById(networkId).getState();
        boolean validStateToImplement = (networkState == Network.State.Implemented || networkState == Network.State.Setup || networkState == Network.State.Allocated);
        if (restartNetwork && !validStateToImplement) {
            CloudRuntimeException ex = new CloudRuntimeException("Failed to implement the network elements and resources as a part of update to network with specified id; network is in wrong state: " + networkState);
            ex.addProxyObject(network, networkId, "networkId");            
            throw ex;
        }

        if (networkOfferingId != null) {
            if (networkOfferingChanged) {
                Transaction txn = Transaction.currentTxn();
                txn.start();
                network.setNetworkOfferingId(networkOfferingId);
                _networksDao.update(networkId, network, newSvcProviders);
                // get all nics using this network
                // log remove usage events for old offering
                // log assign usage events for new offering
                List<NicVO> nics = _nicDao.listByNetworkId(networkId);
                for (NicVO nic : nics) {
                    long vmId = nic.getInstanceId();
                    VMInstanceVO vm = _vmDao.findById(vmId);
                    if (vm == null) {
                        s_logger.error("Vm for nic " + nic.getId() + " not found with Vm Id:" + vmId);
                        continue;
                    }
                    long isDefault = (nic.isDefaultNic()) ? 1 : 0;
                    UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, vm.getAccountId(), vm.getDataCenterIdToDeployIn(), vm.getId(), null, oldNetworkOfferingId, null, 0L);
                    _usageEventDao.persist(usageEvent);
                    usageEvent = new UsageEventVO(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vm.getAccountId(), vm.getDataCenterIdToDeployIn(), vm.getId(), vm.getHostName(), networkOfferingId, null, isDefault);
                    _usageEventDao.persist(usageEvent);
                }
                txn.commit();
            } else {
                network.setNetworkOfferingId(networkOfferingId);
                _networksDao.update(networkId, network, finalizeServicesAndProvidersForNetwork(_configMgr.getNetworkOffering(networkOfferingId), network.getPhysicalNetworkId()));
            }
        } else {
            _networksDao.update(networkId, network);
        }

        // 3) Implement the elements and rules again
        if (restartNetwork) {
            if (network.getState() != Network.State.Allocated) {
                DeployDestination dest = new DeployDestination(_dcDao.findById(network.getDataCenterId()), null, null, null);
                s_logger.debug("Implementing the network " + network + " elements and resources as a part of network update");
                try {
                    if (!changeCidr) {
                        implementNetworkElementsAndResources(dest, context, network, _networkOfferingDao.findById(network.getNetworkOfferingId()));
                    } else {
                        implementNetwork(network.getId(), dest, context);
                    }
                } catch (Exception ex) {
                    s_logger.warn("Failed to implement network " + network + " elements and resources as a part of network update due to ", ex);
                    CloudRuntimeException e = new CloudRuntimeException("Failed to implement network (with specified id) elements and resources as a part of network update");
                    e.addProxyObject(network, networkId, "networkId");                    
                    throw e;
                }
            }
        }

        return getNetwork(network.getId());
    }

    @Override
    public Integer getNetworkRate(long networkId, Long vmId) {
        VMInstanceVO vm = null;
        if (vmId != null) {
            vm = _vmDao.findById(vmId);
        }
        Network network = getNetwork(networkId);
        NetworkOffering ntwkOff = _configMgr.getNetworkOffering(network.getNetworkOfferingId());

        // For default userVm Default network and domR guest/public network, get rate information from the service
        // offering; for other situations get information
        // from the network offering
        boolean isUserVmsDefaultNetwork = false;
        boolean isDomRGuestOrPublicNetwork = false;
        if (vm != null) {
            Nic nic = _nicDao.findByInstanceIdAndNetworkId(networkId, vmId);
            if (vm.getType() == Type.User && nic != null && nic.isDefaultNic()) {
                isUserVmsDefaultNetwork = true;
            } else if (vm.getType() == Type.DomainRouter && ntwkOff != null && (ntwkOff.getTrafficType() == TrafficType.Public || ntwkOff.getTrafficType() == TrafficType.Guest)) {
                isDomRGuestOrPublicNetwork = true;
            }
        }
        if (isUserVmsDefaultNetwork || isDomRGuestOrPublicNetwork) {
            return _configMgr.getServiceOfferingNetworkRate(vm.getServiceOfferingId());
        } else {
            return _configMgr.getNetworkOfferingNetworkRate(ntwkOff.getId());
        }
    }

    Random _rand = new Random(System.currentTimeMillis());

    @Override
    @DB
    public String acquireGuestIpAddress(Network network, String requestedIp) {
        if (requestedIp != null && requestedIp.equals(network.getGateway())) {
            s_logger.warn("Requested ip address " + requestedIp + " is used as a gateway address in network " + network);
            return null;
        }

        Set<Long> availableIps = getAvailableIps(network, requestedIp);

        if (availableIps.isEmpty()) {
            return null;
        }

        Long[] array = availableIps.toArray(new Long[availableIps.size()]);

        if (requestedIp != null) {
            // check that requested ip has the same cidr
            String[] cidr = network.getCidr().split("/");
            boolean isSameCidr = NetUtils.sameSubnetCIDR(requestedIp, NetUtils.long2Ip(array[0]), Integer.parseInt(cidr[1]));
            if (!isSameCidr) {
                s_logger.warn("Requested ip address " + requestedIp + " doesn't belong to the network " + network + " cidr");
                return null;
            } else {
                return requestedIp;
            }
        }

        String result;
        do {
            result = NetUtils.long2Ip(array[_rand.nextInt(array.length)]);
        } while (result.split("\\.")[3].equals("1"));
        return result;
    }

    protected Set<Long> getAvailableIps(Network network, String requestedIp) {
        String[] cidr = network.getCidr().split("/");
        List<String> ips = _nicDao.listIpAddressInNetwork(network.getId());
        Set<Long> allPossibleIps = NetUtils.getAllIpsFromCidr(cidr[0], Integer.parseInt(cidr[1]));
        Set<Long> usedIps = new TreeSet<Long>(); 
        
        for (String ip : ips) {
            if (requestedIp != null && requestedIp.equals(ip)) {
                s_logger.warn("Requested ip address " + requestedIp + " is already in use in network" + network);
                return null;
            }

            usedIps.add(NetUtils.ip2Long(ip));
        }
        if (usedIps.size() != 0) {
            allPossibleIps.removeAll(usedIps);
        }
        return allPossibleIps;
    }
    

    private String getZoneNetworkDomain(long zoneId) {
        return _dcDao.findById(zoneId).getDomain();
    }

    private String getDomainNetworkDomain(long domainId, long zoneId) {
        String networkDomain = null;
        Long searchDomainId = domainId;
        while(searchDomainId != null){
            DomainVO domain = _domainDao.findById(searchDomainId);
            if(domain.getNetworkDomain() != null){
                networkDomain = domain.getNetworkDomain();
                break;
            }
            searchDomainId = domain.getParent();
        }
        if (networkDomain == null) {
            return getZoneNetworkDomain(zoneId);
        }
        return networkDomain;
    }

    @Override
    public String getAccountNetworkDomain(long accountId, long zoneId) {
        String networkDomain = _accountDao.findById(accountId).getNetworkDomain();

        if (networkDomain == null) {
            // get domain level network domain
            return getDomainNetworkDomain(_accountDao.findById(accountId).getDomainId(), zoneId);
        }

        return networkDomain;
    }

    @Override
    public String getGlobalGuestDomainSuffix() {
        return _networkDomain;
    }

    @Override
    public String getStartIpAddress(long networkId) {
        List<VlanVO> vlans = _vlanDao.listVlansByNetworkId(networkId);
        if (vlans.isEmpty()) {
            return null;
        }

        String startIP = vlans.get(0).getIpRange().split("-")[0];

        for (VlanVO vlan : vlans) {
            String startIP1 = vlan.getIpRange().split("-")[0];
            long startIPLong = NetUtils.ip2Long(startIP);
            long startIPLong1 = NetUtils.ip2Long(startIP1);

            if (startIPLong1 < startIPLong) {
                startIP = startIP1;
            }
        }

        return startIP;
    }

    @Override
    public boolean applyStaticNats(List<? extends StaticNat> staticNats, boolean continueOnError) throws ResourceUnavailableException {
        Network network = _networksDao.findById(staticNats.get(0).getNetworkId());
        boolean success = true;

        if (staticNats == null || staticNats.size() == 0) {
            s_logger.debug("There are no static nat rules for the network elements");
            return true;
        }

        // get the list of public ip's owned by the network
        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        List<PublicIp> publicIps = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            for (IPAddressVO userIp : userIps) {
                PublicIp publicIp = new PublicIp(userIp, _vlanDao.findById(userIp.getVlanId()), NetUtils.createSequenceBasedMacAddress(userIp.getMacAddress()));
                publicIps.add(publicIp);
            }
        }

        // static NAT rules can not programmed unless IP is associated with network service provider, so run IP
        // association for the network so as to ensure IP is associated before applying rules (in add state)
        applyIpAssociations(network, false, continueOnError, publicIps);

        // get provider
        String staticNatProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.StaticNat);

        for (NetworkElement ne : _networkElements) {
            try {
                if (!(ne instanceof StaticNatServiceProvider && ne.getName().equalsIgnoreCase(staticNatProvider))) {
                    continue;
                }

                boolean handled = ((StaticNatServiceProvider) ne).applyStaticNats(network, staticNats);
                s_logger.debug("Static Nat for network " + network.getId() + " were " + (handled ? "" : " not") + " handled by " + ne.getName());
            } catch (ResourceUnavailableException e) {
                if (!continueOnError) {
                    throw e;
                }
                s_logger.warn("Problems with " + ne.getName() + " but pushing on", e);
                success = false;
            }
        }

        // For revoked static nat IP, set the vm_id to null, indicate it should be revoked
        for (StaticNat staticNat : staticNats) {
            if (staticNat.isForRevoke()) {
                for (PublicIp publicIp : publicIps) {
                    if (publicIp.getId() == staticNat.getSourceIpAddressId()) {
                        publicIps.remove(publicIp);
                        IPAddressVO ip = _ipAddressDao.findByIdIncludingRemoved(staticNat.getSourceIpAddressId());
                        // ip can't be null, otherwise something wrong happened
                        ip.setAssociatedWithVmId(null);
                        publicIp = new PublicIp(ip, _vlanDao.findById(ip.getVlanId()), NetUtils.createSequenceBasedMacAddress(ip.getMacAddress()));
                        publicIps.add(publicIp);
                        break;
                    }
                }
            }
        }
        
        // if all the rules configured on public IP are revoked then, dis-associate IP with network service provider
        applyIpAssociations(network, true, continueOnError, publicIps);

        return success;
    }

    @Override
    public Long getPodIdForVlan(long vlanDbId) {
        PodVlanMapVO podVlanMaps = _podVlanMapDao.listPodVlanMapsByVlan(vlanDbId);
        if (podVlanMaps == null) {
            return null;
        } else {
            return podVlanMaps.getPodId();
        }
    }

    @DB
    @Override
    public boolean reallocate(VirtualMachineProfile<? extends VMInstanceVO> vm, DataCenterDeployment dest) throws InsufficientCapacityException, ConcurrentOperationException {
        VMInstanceVO vmInstance = _vmDao.findById(vm.getId());
        DataCenterVO dc = _dcDao.findById(vmInstance.getDataCenterIdToDeployIn());
        if (dc.getNetworkType() == NetworkType.Basic) {
            List<NicVO> nics = _nicDao.listByVmId(vmInstance.getId());
            NetworkVO network = _networksDao.findById(nics.get(0).getNetworkId());
            Pair<NetworkVO, NicProfile> profile = new Pair<NetworkVO, NicProfile>(network, null);
            List<Pair<NetworkVO, NicProfile>> profiles = new ArrayList<Pair<NetworkVO, NicProfile>>();
            profiles.add(profile);

            Transaction txn = Transaction.currentTxn();
            txn.start();

            try {
                this.cleanupNics(vm);
                this.allocate(vm, profiles);
            } finally {
                txn.commit();
            }
        }
        return true;
    }

    @Override
    public Map<Service, Set<Provider>> getNetworkOfferingServiceProvidersMap(long networkOfferingId) {
        Map<Service, Set<Provider>> serviceProviderMap = new HashMap<Service, Set<Provider>>();
        List<NetworkOfferingServiceMapVO> map = _ntwkOfferingSrvcDao.listByNetworkOfferingId(networkOfferingId);

        for (NetworkOfferingServiceMapVO instance : map) {
            String service = instance.getService();
            Set<Provider> providers;
            providers = serviceProviderMap.get(service);
            if (providers == null) {
                providers = new HashSet<Provider>();
            }
            providers.add(Provider.getProvider(instance.getProvider()));
            serviceProviderMap.put(Service.getService(service), providers);
        }

        return serviceProviderMap;
    }

    @Override
    public boolean isProviderSupportServiceInNetwork(long networkId, Service service, Provider provider) {
        return _ntwkSrvcDao.canProviderSupportServiceInNetwork(networkId, service, provider);
    }

    protected boolean canUpgrade(Network network, long oldNetworkOfferingId, long newNetworkOfferingId) {
        NetworkOffering oldNetworkOffering = _networkOfferingDao.findByIdIncludingRemoved(oldNetworkOfferingId);
        NetworkOffering newNetworkOffering = _networkOfferingDao.findById(newNetworkOfferingId);

        // can upgrade only Isolated networks
        if (oldNetworkOffering.getGuestType() != GuestType.Isolated) {
            throw new InvalidParameterValueException("NetworkOfferingId can be upgraded only for the network of type " + GuestType.Isolated);
        }

        // security group service should be the same
        if (areServicesSupportedByNetworkOffering(oldNetworkOfferingId, Service.SecurityGroup) != areServicesSupportedByNetworkOffering(newNetworkOfferingId, Service.SecurityGroup)) {
            s_logger.debug("Offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId + " have different securityGroupProperty, can't upgrade");
            return false;
        }

        // Type of the network should be the same
        if (oldNetworkOffering.getGuestType() != newNetworkOffering.getGuestType()) {
            s_logger.debug("Network offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId + " are of different types, can't upgrade");
            return false;
        }

        // tags should be the same
        if (newNetworkOffering.getTags() != null) {
            if (oldNetworkOffering.getTags() == null) {
                s_logger.debug("New network offering id=" + newNetworkOfferingId + " has tags and old network offering id=" + oldNetworkOfferingId + " doesn't, can't upgrade");
                return false;
            }
            if (!oldNetworkOffering.getTags().equalsIgnoreCase(newNetworkOffering.getTags())) {
                s_logger.debug("Network offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId + " have different tags, can't upgrade");
                return false;
            }
        }

        // Traffic types should be the same
        if (oldNetworkOffering.getTrafficType() != newNetworkOffering.getTrafficType()) {
            s_logger.debug("Network offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId + " have different traffic types, can't upgrade");
            return false;
        }

        // specify vlan should be the same
        if (oldNetworkOffering.getSpecifyVlan() != newNetworkOffering.getSpecifyVlan()) {
            s_logger.debug("Network offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId + " have different values for specifyVlan, can't upgrade");
            return false;
        }

        // specify ipRanges should be the same
        if (oldNetworkOffering.getSpecifyIpRanges() != newNetworkOffering.getSpecifyIpRanges()) {
            s_logger.debug("Network offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId + " have different values for specifyIpRangess, can't upgrade");
            return false;
        }

        // Check all ips
        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        List<PublicIp> publicIps = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            for (IPAddressVO userIp : userIps) {
                PublicIp publicIp = new PublicIp(userIp, _vlanDao.findById(userIp.getVlanId()), NetUtils.createSequenceBasedMacAddress(userIp.getMacAddress()));
                publicIps.add(publicIp);
            }
        }
        if (oldNetworkOffering.isConserveMode() && !newNetworkOffering.isConserveMode()) {
            if (!canIpsUsedForNonConserve(publicIps)) {
                return false;
            }
        }

        return canIpsUseOffering(publicIps, newNetworkOfferingId);
    }

    protected boolean canUpgradeProviders(long oldNetworkOfferingId, long newNetworkOfferingId) {
        // list of services and providers should be the same
        Map<Service, Set<Provider>> newServices = getNetworkOfferingServiceProvidersMap(newNetworkOfferingId);
        Map<Service, Set<Provider>> oldServices = getNetworkOfferingServiceProvidersMap(oldNetworkOfferingId);

        if (newServices.size() < oldServices.size()) {
            s_logger.debug("Network offering downgrade is not allowed: number of supported services for the new offering " + newNetworkOfferingId + " is less than the old offering " + oldNetworkOfferingId);
            return false;
        }

        for (Service service : oldServices.keySet()) {

            // 1)check that all old services are present in the new network offering
            if (!newServices.containsKey(service)) {
                s_logger.debug("New service offering doesn't have " + service + " service present in the old service offering, downgrade is not allowed");
                return false;
            }

            Set<Provider> newProviders = newServices.get(service);
            Set<Provider> oldProviders = oldServices.get(service);

            // 2) Can upgrade only from internal provider to external provider. Any other combinations are not allowed
            for (Provider oldProvider : oldProviders) {
                if (newProviders.contains(oldProvider)) {
                    s_logger.trace("New list of providers contains provider " + oldProvider);
                    continue;
                }
                // iterate through new providers and check that the old provider can upgrade
                for (Provider newProvider : newProviders) {
                    if (!(!oldProvider.isExternal() && newProvider.isExternal())) {
                        s_logger.debug("Can't downgrade from network offering " + oldNetworkOfferingId + " to the new networkOffering " + newNetworkOfferingId);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_CREATE, eventDescription = "Creating Physical Network", create = true)
    public PhysicalNetwork createPhysicalNetwork(Long zoneId, String vnetRange, String networkSpeed, List<String> 
    isolationMethods, String broadcastDomainRangeStr, Long domainId, List<String> tags, String name) {

        // Check if zone exists
        if (zoneId == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }

        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }

        if (Grouping.AllocationState.Enabled == zone.getAllocationState()) {
            // TBD: Send uuid instead of zoneId; may have to hardcode tablename in call to addProxyObject().
            throw new PermissionDeniedException("Cannot create PhysicalNetwork since the Zone is currently enabled, zone Id: " + zoneId);
        }

        NetworkType zoneType = zone.getNetworkType();

        if (zoneType == NetworkType.Basic) {
            if (!_physicalNetworkDao.listByZone(zoneId).isEmpty()) {
                // TBD: Send uuid instead of zoneId; may have to hardcode tablename in call to addProxyObject().
                throw new CloudRuntimeException("Cannot add the physical network to basic zone id: " + zoneId + ", there is a physical network already existing in this basic Zone");
            }
        }
        if (tags != null && tags.size() > 1) {
            throw new InvalidParameterException("Only one tag can be specified for a physical network at this time");
        }

        if (isolationMethods != null && isolationMethods.size() > 1) {
            throw new InvalidParameterException("Only one isolationMethod can be specified for a physical network at this time");
        }

        int vnetStart = 0;
        int vnetEnd = 0;
        if (vnetRange != null) {
            // Verify zone type
            if (zoneType == NetworkType.Basic
                    || (zoneType == NetworkType.Advanced && zone.isSecurityGroupEnabled())) {
                throw new InvalidParameterValueException("Can't add vnet range to the physical network in the zone that supports " + zoneType + " network, Security Group enabled: " + zone.isSecurityGroupEnabled());
            }

            String[] tokens = vnetRange.split("-");
            try {
                vnetStart = Integer.parseInt(tokens[0]);
                if (tokens.length == 1) {
                    vnetEnd = vnetStart;
                } else {
                    vnetEnd = Integer.parseInt(tokens[1]);
                }
            } catch (NumberFormatException e) {
                throw new InvalidParameterValueException("Please specify valid integers for the vlan range.");
            }
            
            //check for vnet conflicts with other physical network(s) in the zone
            checkGuestVnetsConflicts(zoneId, vnetStart, vnetEnd, null);

            if ((vnetStart > vnetEnd) || (vnetStart < 0) || (vnetEnd > 4096)) {
                s_logger.warn("Invalid vnet range: start range:" + vnetStart + " end range:" + vnetEnd);
                throw new InvalidParameterValueException("Vnet range should be between 0-4096 and start range should be lesser than or equal to end range");
            }
        }

        BroadcastDomainRange broadcastDomainRange = null;
        if (broadcastDomainRangeStr != null && !broadcastDomainRangeStr.isEmpty()) {
            try {
                broadcastDomainRange = PhysicalNetwork.BroadcastDomainRange.valueOf(broadcastDomainRangeStr.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve broadcastDomainRange '" + broadcastDomainRangeStr + "' to a supported value {Pod or Zone}");
            }

            // in Acton release you can specify only Zone broadcastdomain type in Advance zone, and Pod in Basic
            if (zoneType == NetworkType.Basic && broadcastDomainRange != null && broadcastDomainRange != BroadcastDomainRange.POD) {
                throw new InvalidParameterValueException("Basic zone can have broadcast domain type of value " + BroadcastDomainRange.POD + " only");
            } else if (zoneType == NetworkType.Advanced && broadcastDomainRange != null && broadcastDomainRange != BroadcastDomainRange.ZONE) {
                throw new InvalidParameterValueException("Advance zone can have broadcast domain type of value " + BroadcastDomainRange.ZONE + " only");
            }
        }

        if (broadcastDomainRange == null) {
            if (zoneType == NetworkType.Basic) {
                broadcastDomainRange = PhysicalNetwork.BroadcastDomainRange.POD;
            } else {
                broadcastDomainRange = PhysicalNetwork.BroadcastDomainRange.ZONE;
            }
        }

        Transaction txn = Transaction.currentTxn();
        try {
            txn.start();
            // Create the new physical network in the database
            long id = _physicalNetworkDao.getNextInSequence(Long.class, "id");
            PhysicalNetworkVO pNetwork = new PhysicalNetworkVO(id, zoneId, vnetRange, networkSpeed, domainId, broadcastDomainRange, name);
            pNetwork.setTags(tags);
            pNetwork.setIsolationMethods(isolationMethods);

            pNetwork = _physicalNetworkDao.persist(pNetwork);

            // Add vnet entries for the new zone if zone type is Advanced
            if (vnetRange != null) {
                _dcDao.addVnet(zone.getId(), pNetwork.getId(), vnetStart, vnetEnd);
            }

            // add VirtualRouter as the default network service provider
            addDefaultVirtualRouterToPhysicalNetwork(pNetwork.getId());

            // add security group provider to the physical network
            addDefaultSecurityGroupProviderToPhysicalNetwork(pNetwork.getId());
            
            // add VPCVirtualRouter as the defualt network service provider
            addDefaultVpcVirtualRouterToPhysicalNetwork(pNetwork.getId());

            txn.commit();
            return pNetwork;
        } catch (Exception ex) {
            s_logger.warn("Exception: ", ex);
            throw new CloudRuntimeException("Fail to create a physical network");
        }
    }

    @Override
    public Pair<List<? extends PhysicalNetwork>, Integer> searchPhysicalNetworks(Long id, Long zoneId, String keyword, Long startIndex, Long pageSize, String name) {
        Filter searchFilter = new Filter(PhysicalNetworkVO.class, "id", Boolean.TRUE, startIndex, pageSize);
        SearchCriteria<PhysicalNetworkVO> sc = _physicalNetworkDao.createSearchCriteria();

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (zoneId != null) {
            sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.LIKE, "%" + name + "%");
        }

        Pair<List<PhysicalNetworkVO>, Integer> result =  _physicalNetworkDao.searchAndCount(sc, searchFilter);
        return new Pair<List<? extends PhysicalNetwork>, Integer>(result.first(), result.second());
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_UPDATE, eventDescription = "updating physical network", async = true)
    public PhysicalNetwork updatePhysicalNetwork(Long id, String networkSpeed, List<String> tags, String newVnetRangeString, String state) {

        // verify input parameters
        PhysicalNetworkVO network = _physicalNetworkDao.findById(id);
        if (network == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Physical Network with specified id doesn't exist in the system");
            ex.addProxyObject(network, id, "physicalNetworkId");            
            throw ex;
        }

        // if zone is of Basic type, don't allow to add vnet range
        DataCenter zone = _dcDao.findById(network.getDataCenterId());
        if (zone == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Zone with id=" + network.getDataCenterId() + " doesn't exist in the system");
            ex.addProxyObject(zone, network.getDataCenterId(), "dataCenterId");            
            throw ex;
        }
        if (newVnetRangeString != null) {
            if (zone.getNetworkType() == NetworkType.Basic
                    || (zone.getNetworkType() == NetworkType.Advanced && zone.isSecurityGroupEnabled())) {
                throw new InvalidParameterValueException("Can't add vnet range to the physical network in the zone that supports " + zone.getNetworkType() + " network, Security Group enabled: "
                        + zone.isSecurityGroupEnabled());
            }
        }

        if (tags != null && tags.size() > 1) {
            throw new InvalidParameterException("Unable to support more than one tag on network yet");
        }

        PhysicalNetwork.State networkState = null;
        if (state != null && !state.isEmpty()) {
            try {
                networkState = PhysicalNetwork.State.valueOf(state);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve state '" + state + "' to a supported value {Enabled or Disabled}");
            }
        }

        if (state != null) {
            network.setState(networkState);
        }

        if (tags != null) {
            network.setTags(tags);
        }

        if (networkSpeed != null) {
            network.setSpeed(networkSpeed);
        }

        // Vnet range can be extended only
        boolean replaceVnet = false;
        ArrayList<Pair<Integer, Integer>> vnetsToAdd = new ArrayList<Pair<Integer, Integer>>(2);

        if (newVnetRangeString != null) {
            Integer newStartVnet = 0;
            Integer newEndVnet = 0;
            String[] newVnetRange = newVnetRangeString.split("-");
            int maxVnet = 4096;
            // for GRE phynets allow up to 32bits
            // TODO: Not happy about this test.  
            // What about guru-like objects for physical networs?
            s_logger.debug("ISOLATION METHODS:" + network.getIsolationMethods());
            // Java does not have unsigned types...
            if (network.getIsolationMethods().contains("GRE")) { 
                maxVnet = (int)(Math.pow(2, 32)-1);
            }
            String rangeMessage = " between 0 and " + maxVnet; 
            if (newVnetRange.length < 2) {
                throw new InvalidParameterValueException("Please provide valid vnet range" + rangeMessage);
            }

            if (newVnetRange[0] == null || newVnetRange[1] == null) {
                throw new InvalidParameterValueException("Please provide valid vnet range" + rangeMessage);
            }

            try {
                newStartVnet = Integer.parseInt(newVnetRange[0]);
                newEndVnet = Integer.parseInt(newVnetRange[1]);
            } catch (NumberFormatException e) {
                s_logger.warn("Unable to parse vnet range:", e);
                throw new InvalidParameterValueException("Please provide valid vnet range" + rangeMessage);
            }
            if (newStartVnet < 0 || newEndVnet > maxVnet) {
                throw new InvalidParameterValueException("Vnet range has to be" + rangeMessage);
            }

            if (newStartVnet > newEndVnet) {
                throw new InvalidParameterValueException("Vnet range has to be" + rangeMessage + " and start range should be lesser than or equal to stop range");
            }
            
            //check if new vnet conflicts with vnet ranges of other physical networks
            checkGuestVnetsConflicts(network.getDataCenterId(), newStartVnet, newEndVnet, network.getId());

            if (physicalNetworkHasAllocatedVnets(network.getDataCenterId(), network.getId())) {
                String[] existingRange = network.getVnet().split("-");
                int existingStartVnet = Integer.parseInt(existingRange[0]);
                int existingEndVnet = Integer.parseInt(existingRange[1]);

                // check if vnet is being extended
                if (newStartVnet.intValue() > existingStartVnet || newEndVnet.intValue() < existingEndVnet) {
                    throw new InvalidParameterValueException("Can't shrink existing vnet range as it the range has vnets allocated. Only extending existing vnet is supported");
                }

                if (newStartVnet < existingStartVnet) {
                    vnetsToAdd.add(new Pair<Integer, Integer>(newStartVnet, existingStartVnet - 1));
                }

                if (newEndVnet > existingEndVnet) {
                    vnetsToAdd.add(new Pair<Integer, Integer>(existingEndVnet + 1, newEndVnet));
                }

            } else {
                vnetsToAdd.add(new Pair<Integer, Integer>(newStartVnet, newEndVnet));
                replaceVnet = true;
            }
        }

        if (newVnetRangeString != null) {
            network.setVnet(newVnetRangeString);
        }

        _physicalNetworkDao.update(id, network);

        if (replaceVnet) {
            s_logger.debug("Deleting existing vnet range for the physicalNetwork id= " + id + " and zone id=" + network.getDataCenterId() + " as a part of updatePhysicalNetwork call");
            _dcDao.deleteVnet(network.getId());
        }

        for (Pair<Integer, Integer> vnetToAdd : vnetsToAdd) {
            s_logger.debug("Adding vnet range " + vnetToAdd.first() + "-" + vnetToAdd.second() + " for the physicalNetwork id= " + id + " and zone id=" + network.getDataCenterId()
                    + " as a part of updatePhysicalNetwork call");
            _dcDao.addVnet(network.getDataCenterId(), network.getId(), vnetToAdd.first(), vnetToAdd.second());
        }

        return network;
    }

    protected void checkGuestVnetsConflicts(long zoneId, int newStartVnet, int newEndVnet, Long pNtwkIdToSkip) {
        List<? extends PhysicalNetwork> pNtwks = _physicalNetworkDao.listByZone(zoneId);
        for (PhysicalNetwork pNtwk : pNtwks) {
            // skip my own network and networks that don't have vnet range set
            if ((pNtwk.getVnet() == null || pNtwk.getVnet().isEmpty()) || (pNtwkIdToSkip != null && pNtwkIdToSkip == pNtwk.getId())) {
                continue;
            }
            String[] existingRange = pNtwk.getVnet().split("-");
            int startVnet = Integer.parseInt(existingRange[0]);
            int endVnet = Integer.parseInt(existingRange[1]);
            if ((newStartVnet >= startVnet && newStartVnet <= endVnet)
                    || (newEndVnet <= endVnet && newEndVnet >= startVnet)) {
                throw new InvalidParameterValueException("Vnet range for physical network conflicts with another " +
                		"physical network's vnet in the zone");
            } 
        }
    }

    private boolean physicalNetworkHasAllocatedVnets(long zoneId, long physicalNetworkId) {
        return !_dcDao.listAllocatedVnets(physicalNetworkId).isEmpty();
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_DELETE, eventDescription = "deleting physical network", async = true)
    @DB
    public boolean deletePhysicalNetwork(Long physicalNetworkId) {

        // verify input parameters
        PhysicalNetworkVO pNetwork = _physicalNetworkDao.findById(physicalNetworkId);
        if (pNetwork == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Physical Network with specified id doesn't exist in the system");
            ex.addProxyObject(pNetwork, physicalNetworkId, "physicalNetworkId");            
            throw ex;
        }

        checkIfPhysicalNetworkIsDeletable(physicalNetworkId);

        Transaction txn = Transaction.currentTxn();
        txn.start();
        // delete vlans for this zone
        List<VlanVO> vlans = _vlanDao.listVlansByPhysicalNetworkId(physicalNetworkId);
        for (VlanVO vlan : vlans) {
            _vlanDao.remove(vlan.getId());
        }

        // Delete networks
        List<NetworkVO> networks = _networksDao.listByPhysicalNetwork(physicalNetworkId);
        if (networks != null && !networks.isEmpty()) {
            for (NetworkVO network : networks) {
                _networksDao.remove(network.getId());
            }
        }

        // delete vnets
        _dcDao.deleteVnet(physicalNetworkId);

        // delete service providers
        List<PhysicalNetworkServiceProviderVO> providers = _pNSPDao.listBy(physicalNetworkId);
        
        for(PhysicalNetworkServiceProviderVO provider : providers){
            try {
                deleteNetworkServiceProvider(provider.getId());
            }catch (ResourceUnavailableException e) {
                s_logger.warn("Unable to complete destroy of the physical network provider: " + provider.getProviderName() + ", id: "+ provider.getId(), e);
                return false;
            } catch (ConcurrentOperationException e) {
                s_logger.warn("Unable to complete destroy of the physical network provider: " + provider.getProviderName() + ", id: "+ provider.getId(), e);
                return false;
            }
        }

        // delete traffic types
        _pNTrafficTypeDao.deleteTrafficTypes(physicalNetworkId);

        boolean success = _physicalNetworkDao.remove(physicalNetworkId);
        
        txn.commit();

        return success;
    }

    @DB
    private void checkIfPhysicalNetworkIsDeletable(Long physicalNetworkId) {
        List<List<String>> tablesToCheck = new ArrayList<List<String>>();

        List<String> vnet = new ArrayList<String>();
        vnet.add(0, "op_dc_vnet_alloc");
        vnet.add(1, "physical_network_id");
        vnet.add(2, "there are allocated vnets for this physical network");
        tablesToCheck.add(vnet);

        List<String> networks = new ArrayList<String>();
        networks.add(0, "networks");
        networks.add(1, "physical_network_id");
        networks.add(2, "there are networks associated to this physical network");
        tablesToCheck.add(networks);

        /*
         * List<String> privateIP = new ArrayList<String>();
         * privateIP.add(0, "op_dc_ip_address_alloc");
         * privateIP.add(1, "data_center_id");
         * privateIP.add(2, "there are private IP addresses allocated for this zone");
         * tablesToCheck.add(privateIP);
         */

        List<String> publicIP = new ArrayList<String>();
        publicIP.add(0, "user_ip_address");
        publicIP.add(1, "physical_network_id");
        publicIP.add(2, "there are public IP addresses allocated for this physical network");
        tablesToCheck.add(publicIP);

        for (List<String> table : tablesToCheck) {
            String tableName = table.get(0);
            String column = table.get(1);
            String errorMsg = table.get(2);

            String dbName = "cloud";

            String selectSql = "SELECT * FROM `" + dbName + "`.`" + tableName + "` WHERE " + column + " = ?";

            if (tableName.equals("networks")) {
                selectSql += " AND removed is NULL";
            }

            if (tableName.equals("op_dc_vnet_alloc")) {
                selectSql += " AND taken IS NOT NULL";
            }

            if (tableName.equals("user_ip_address")) {
                selectSql += " AND state!='Free'";
            }

            if (tableName.equals("op_dc_ip_address_alloc")) {
                selectSql += " AND taken IS NOT NULL";
            }

            Transaction txn = Transaction.currentTxn();
            try {
                PreparedStatement stmt = txn.prepareAutoCloseStatement(selectSql);
                stmt.setLong(1, physicalNetworkId);
                ResultSet rs = stmt.executeQuery();
                if (rs != null && rs.next()) {
                    throw new CloudRuntimeException("The Physical Network is not deletable because " + errorMsg);
                }
            } catch (SQLException ex) {
                throw new CloudRuntimeException("The Management Server failed to detect if physical network is deletable. Please contact Cloud Support.");
            }
        }

    }

    @Override
    public List<? extends Service> listNetworkServices(String providerName) {

        Provider provider = null;
        if (providerName != null) {
            provider = Network.Provider.getProvider(providerName);
            if (provider == null) {
                throw new InvalidParameterValueException("Invalid Network Service Provider=" + providerName);
            }
        }

        if (provider != null) {
            NetworkElement element = getElementImplementingProvider(providerName);
            if (element == null) {
                throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + providerName + "'");
            }
            return new ArrayList<Service>(element.getCapabilities().keySet());
        } else {
            return Service.listAllServices();
        }
    }

    @Override
    public List<? extends Provider> listSupportedNetworkServiceProviders(String serviceName) {
        Network.Service service = null;
        if (serviceName != null) {
            service = Network.Service.getService(serviceName);
            if (service == null) {
                throw new InvalidParameterValueException("Invalid Network Service=" + serviceName);
            }
        }

        Set<Provider> supportedProviders = new HashSet<Provider>();

        if (service != null) {
            supportedProviders.addAll(s_serviceToImplementedProvidersMap.get(service));
        } else {
            for (List<Provider> pList : s_serviceToImplementedProvidersMap.values()) {
                supportedProviders.addAll(pList);
            }
        }

        return new ArrayList<Provider>(supportedProviders);
    }

    @Override
    public Provider getDefaultUniqueProviderForService(String serviceName) {
        List<? extends Provider> providers = listSupportedNetworkServiceProviders(serviceName);
        if (providers.isEmpty()) {
            throw new CloudRuntimeException("No providers supporting service " + serviceName + " found in cloudStack");
        }
        if (providers.size() > 1) {
            throw new CloudRuntimeException("More than 1 provider supporting service " + serviceName + " found in cloudStack");
        }

        return providers.get(0);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_CREATE, eventDescription = "Creating Physical Network ServiceProvider", create = true)
    public PhysicalNetworkServiceProvider addProviderToPhysicalNetwork(Long physicalNetworkId, String providerName, Long destinationPhysicalNetworkId, List<String> enabledServices) {

        // verify input parameters
        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Physical Network with specified id doesn't exist in the system");
            ex.addProxyObject(network, physicalNetworkId, "physicalNetworkId");
            throw ex;
        }

        // verify input parameters
        if (destinationPhysicalNetworkId != null) {
            PhysicalNetworkVO destNetwork = _physicalNetworkDao.findById(destinationPhysicalNetworkId);
            if (destNetwork == null) {
                InvalidParameterValueException ex = new InvalidParameterValueException("Destination Physical Network with specified id doesn't exist in the system");
                ex.addProxyObject(destNetwork, destinationPhysicalNetworkId, "destinationPhysicalNetworkId");                
                throw ex;
            }
        }

        if (providerName != null) {
            Provider provider = Network.Provider.getProvider(providerName);
            if (provider == null) {
                throw new InvalidParameterValueException("Invalid Network Service Provider=" + providerName);
            }
        }

        if (_pNSPDao.findByServiceProvider(physicalNetworkId, providerName) != null) {
            // TBD: send uuid instead of physicalNetworkId.
            throw new CloudRuntimeException("The '" + providerName + "' provider already exists on physical network : " + physicalNetworkId);
        }

        // check if services can be turned off
        NetworkElement element = getElementImplementingProvider(providerName);
        if (element == null) {
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + providerName + "'");
        }
        List<Service> services = new ArrayList<Service>();

        if (enabledServices != null) {
            if (!element.canEnableIndividualServices()) {
                if (enabledServices.size() != element.getCapabilities().keySet().size()) {
                    throw new InvalidParameterValueException("Cannot enable subset of Services, Please specify the complete list of Services for this Service Provider '" + providerName + "'");
                }
            }

            // validate Services
            boolean addGatewayService = false;
            for (String serviceName : enabledServices) {
                Network.Service service = Network.Service.getService(serviceName);
                if (service == null || service == Service.Gateway) {
                    throw new InvalidParameterValueException("Invalid Network Service specified=" + serviceName);
                } else if (service == Service.SourceNat) {
                    addGatewayService = true;
                }

                // check if the service is provided by this Provider
                if (!element.getCapabilities().containsKey(service)) {
                    throw new InvalidParameterValueException(providerName + " Provider cannot provide this Service specified=" + serviceName);
                }
                services.add(service);
            }

            if (addGatewayService) {
                services.add(Service.Gateway);
            }
        } else {
            // enable all the default services supported by this element.
            services = new ArrayList<Service>(element.getCapabilities().keySet());
        }

        Transaction txn = Transaction.currentTxn();
        try {
            txn.start();
            // Create the new physical network in the database
            PhysicalNetworkServiceProviderVO nsp = new PhysicalNetworkServiceProviderVO(physicalNetworkId, providerName);
            // set enabled services
            nsp.setEnabledServices(services);

            if (destinationPhysicalNetworkId != null) {
                nsp.setDestinationPhysicalNetworkId(destinationPhysicalNetworkId);
            }
            nsp = _pNSPDao.persist(nsp);

            txn.commit();
            return nsp;
        } catch (Exception ex) {
            s_logger.warn("Exception: ", ex);
            throw new CloudRuntimeException("Fail to add a provider to physical network");
        }

    }

    @Override
    public Pair<List<? extends PhysicalNetworkServiceProvider>, Integer> listNetworkServiceProviders(Long physicalNetworkId,
            String name, String state, Long startIndex, Long pageSize) {

        Filter searchFilter = new Filter(PhysicalNetworkServiceProviderVO.class, "id", false, startIndex, pageSize);
        SearchBuilder<PhysicalNetworkServiceProviderVO> sb = _pNSPDao.createSearchBuilder();
        SearchCriteria<PhysicalNetworkServiceProviderVO> sc = sb.create();

        if (physicalNetworkId != null) {
            sc.addAnd("physicalNetworkId", Op.EQ, physicalNetworkId);
        }

        if (name != null) {
            sc.addAnd("providerName", Op.EQ, name);
        }

        if (state != null) {
            sc.addAnd("state", Op.EQ, state);
        }

        Pair<List<PhysicalNetworkServiceProviderVO>, Integer> result =  _pNSPDao.searchAndCount(sc, searchFilter);
        return new Pair<List<? extends PhysicalNetworkServiceProvider>, Integer>(result.first(), result.second());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_UPDATE, eventDescription = "Updating physical network ServiceProvider", async = true)
    public PhysicalNetworkServiceProvider updateNetworkServiceProvider(Long id, String stateStr, List<String> enabledServices) {

        PhysicalNetworkServiceProviderVO provider = _pNSPDao.findById(id);
        if (provider == null) {
            throw new InvalidParameterValueException("Network Service Provider id=" + id + "doesn't exist in the system");
        }

        NetworkElement element = getElementImplementingProvider(provider.getProviderName());
        if (element == null) {
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getProviderName() + "'");
        }

        PhysicalNetworkServiceProvider.State state = null;
        if (stateStr != null && !stateStr.isEmpty()) {
            try {
                state = PhysicalNetworkServiceProvider.State.valueOf(stateStr);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve state '" + stateStr + "' to a supported value {Enabled or Disabled}");
            }
        }

        boolean update = false;

        if (state != null) {
            if (state == PhysicalNetworkServiceProvider.State.Shutdown) {
                throw new InvalidParameterValueException("Updating the provider state to 'Shutdown' is not supported");
            }

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("updating state of the service provider id=" + id + " on physical network: " + provider.getPhysicalNetworkId() + " to state: " + stateStr);
            }
            switch (state) {
            case Enabled:
                if (element != null && element.isReady(provider)) {
                    provider.setState(PhysicalNetworkServiceProvider.State.Enabled);
                    update = true;
                } else {
                    throw new CloudRuntimeException("Provider is not ready, cannot Enable the provider, please configure the provider first");
                }
                break;
            case Disabled:
                // do we need to do anything for the provider instances before disabling?
                provider.setState(PhysicalNetworkServiceProvider.State.Disabled);
                update = true;
                break;
            }
        }

        if (enabledServices != null) {
            // check if services can be turned of
            if (!element.canEnableIndividualServices()) {
                throw new InvalidParameterValueException("Cannot update set of Services for this Service Provider '" + provider.getProviderName() + "'");
            }

            // validate Services
            List<Service> services = new ArrayList<Service>();
            for (String serviceName : enabledServices) {
                Network.Service service = Network.Service.getService(serviceName);
                if (service == null) {
                    throw new InvalidParameterValueException("Invalid Network Service specified=" + serviceName);
                }
                services.add(service);
            }
            // set enabled services
            provider.setEnabledServices(services);
            update = true;
        }

        if (update) {
            _pNSPDao.update(id, provider);
        }
        return provider;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_DELETE, eventDescription = "Deleting physical network ServiceProvider", async = true)
    public boolean deleteNetworkServiceProvider(Long id) throws ConcurrentOperationException, ResourceUnavailableException {
        PhysicalNetworkServiceProviderVO provider = _pNSPDao.findById(id);

        if (provider == null) {
            throw new InvalidParameterValueException("Network Service Provider id=" + id + "doesn't exist in the system");
        }

        // check if there are networks using this provider
        List<NetworkVO> networks = _networksDao.listByPhysicalNetworkAndProvider(provider.getPhysicalNetworkId(), provider.getProviderName());
        if (networks != null && !networks.isEmpty()) {
            throw new CloudRuntimeException("Provider is not deletable because there are active networks using this provider, please upgrade these networks to new network offerings");
        }

        User callerUser = _accountMgr.getActiveUser(UserContext.current().getCallerUserId());
        Account callerAccount = _accountMgr.getActiveAccountById(callerUser.getAccountId());
        // shutdown the provider instances
        ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Shutting down the service provider id=" + id + " on physical network: " + provider.getPhysicalNetworkId());
        }
        NetworkElement element = getElementImplementingProvider(provider.getProviderName());
        if (element == null) {
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getProviderName() + "'");
        }

        if (element != null && element.shutdownProviderInstances(provider, context)) {
            provider.setState(PhysicalNetworkServiceProvider.State.Shutdown);
        }

        return _pNSPDao.remove(id);
    }

    @Override
    public PhysicalNetwork getPhysicalNetwork(Long physicalNetworkId) {
        return _physicalNetworkDao.findById(physicalNetworkId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_CREATE, eventDescription = "Creating Physical Network", async = true)
    public PhysicalNetwork getCreatedPhysicalNetwork(Long physicalNetworkId) {
        return getPhysicalNetwork(physicalNetworkId);
    }

    @Override
    public PhysicalNetworkServiceProvider getPhysicalNetworkServiceProvider(Long providerId) {
        return _pNSPDao.findById(providerId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_CREATE, eventDescription = "Creating Physical Network ServiceProvider", async = true)
    public PhysicalNetworkServiceProvider getCreatedPhysicalNetworkServiceProvider(Long providerId) {
        return getPhysicalNetworkServiceProvider(providerId);
    }

    @Override
    public long findPhysicalNetworkId(long zoneId, String tag, TrafficType trafficType) {
        List<PhysicalNetworkVO> pNtwks = new ArrayList<PhysicalNetworkVO>();
        if (trafficType != null) {
            pNtwks = _physicalNetworkDao.listByZoneAndTrafficType(zoneId, trafficType);
        } else {
            pNtwks = _physicalNetworkDao.listByZone(zoneId);
        }
        
        if (pNtwks.isEmpty()) {
            throw new InvalidParameterValueException("Unable to find physical network in zone id=" + zoneId);
        }

        if (pNtwks.size() > 1) {
            if (tag == null) {
                throw new InvalidParameterValueException("More than one physical networks exist in zone id=" + zoneId + " and no tags are specified in order to make a choice");
            }

            Long pNtwkId = null;
            for (PhysicalNetwork pNtwk : pNtwks) {
                if (pNtwk.getTags().contains(tag)) {
                    s_logger.debug("Found physical network id=" + pNtwk.getId() + " based on requested tags " + tag);
                    pNtwkId = pNtwk.getId();
                    break;
                }
            }
            if (pNtwkId == null) {
                throw new InvalidParameterValueException("Unable to find physical network which match the tags " + tag);
            }
            return pNtwkId;
        } else {
            return pNtwks.get(0).getId();
        }
    }

    @Override
    public List<Long> listNetworkOfferingsForUpgrade(long networkId) {
        List<Long> offeringsToReturn = new ArrayList<Long>();
        NetworkOffering originalOffering = _configMgr.getNetworkOffering(getNetwork(networkId).getNetworkOfferingId());

        boolean securityGroupSupportedByOriginalOff = areServicesSupportedByNetworkOffering(originalOffering.getId(), Service.SecurityGroup);

        // security group supported property should be the same

        List<Long> offerings = _networkOfferingDao.getOfferingIdsToUpgradeFrom(originalOffering);

        for (Long offeringId : offerings) {
            if (areServicesSupportedByNetworkOffering(offeringId, Service.SecurityGroup) == securityGroupSupportedByOriginalOff) {
                offeringsToReturn.add(offeringId);
            }
        }

        return offeringsToReturn;
    }

    private boolean cleanupNetworkResources(long networkId, Account caller, long callerUserId) {
        boolean success = true;
        Network network = getNetwork(networkId);

        //remove all PF/Static Nat rules for the network
        try {
            if (_rulesMgr.revokeAllPFStaticNatRulesForNetwork(networkId, callerUserId, caller)) {
                s_logger.debug("Successfully cleaned up portForwarding/staticNat rules for network id=" + networkId);
            } else {
                success = false;
                s_logger.warn("Failed to release portForwarding/StaticNat rules as a part of network id=" + networkId + " cleanup");
            }
        } catch (ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            s_logger.warn("Failed to release portForwarding/StaticNat rules as a part of network id=" + networkId + " cleanup due to resourceUnavailable ", ex);
        }

        //remove all LB rules for the network
        if (_lbMgr.removeAllLoadBalanacersForNetwork(networkId, caller, callerUserId)) {
            s_logger.debug("Successfully cleaned up load balancing rules for network id=" + networkId);
        } else {
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            success = false;
            s_logger.warn("Failed to cleanup LB rules as a part of network id=" + networkId + " cleanup");
        }

        //revoke all firewall rules for the network
        try {
            if (_firewallMgr.revokeAllFirewallRulesForNetwork(networkId, callerUserId, caller)) {
                s_logger.debug("Successfully cleaned up firewallRules rules for network id=" + networkId);
            } else {
                success = false;
                s_logger.warn("Failed to cleanup Firewall rules as a part of network id=" + networkId + " cleanup");
            }
        } catch (ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            s_logger.warn("Failed to cleanup Firewall rules as a part of network id=" + networkId + " cleanup due to resourceUnavailable ", ex);
        }
        
        //revoke all network ACLs for network
        try {
            if (_networkACLMgr.revokeAllNetworkACLsForNetwork(networkId, callerUserId, caller)) {
                s_logger.debug("Successfully cleaned up NetworkACLs for network id=" + networkId);
            } else {
                success = false;
                s_logger.warn("Failed to cleanup NetworkACLs as a part of network id=" + networkId + " cleanup");
            }
        } catch (ResourceUnavailableException ex) {
            success = false;
            s_logger.warn("Failed to cleanup Network ACLs as a part of network id=" + networkId +
                    " cleanup due to resourceUnavailable ", ex);
        }

        //release all ip addresses
        List<IPAddressVO> ipsToRelease = _ipAddressDao.listByAssociatedNetwork(networkId, null);
        for (IPAddressVO ipToRelease : ipsToRelease) {
            if (ipToRelease.getVpcId() == null) {
                IPAddressVO ip = markIpAsUnavailable(ipToRelease.getId());
                assert (ip != null) : "Unable to mark the ip address id=" + ipToRelease.getId() + " as unavailable.";
            } else {
                _vpcMgr.unassignIPFromVpcNetwork(ipToRelease.getId(), network.getId());
            }
        }

        try {
            if (!applyIpAssociations(network, true)) {
                s_logger.warn("Unable to apply ip address associations for " + network);
                success = false;
            }
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        return success;
    }

    private boolean shutdownNetworkResources(long networkId, Account caller, long callerUserId) {
        // This method cleans up network rules on the backend w/o touching them in the DB
        boolean success = true;

        // Mark all PF rules as revoked and apply them on the backend (not in the DB)
        List<PortForwardingRuleVO> pfRules = _portForwardingRulesDao.listByNetwork(networkId);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + pfRules.size() + " port forwarding rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        for (PortForwardingRuleVO pfRule : pfRules) {
            s_logger.trace("Marking pf rule " + pfRule + " with Revoke state");
            pfRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(pfRules, true, false)) {
                s_logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // Mark all static rules as revoked and apply them on the backend (not in the DB)
        List<FirewallRuleVO> firewallStaticNatRules = _firewallDao.listByNetworkAndPurpose(networkId, Purpose.StaticNat);
        List<StaticNatRule> staticNatRules = new ArrayList<StaticNatRule>();
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + firewallStaticNatRules.size() + " static nat rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        for (FirewallRuleVO firewallStaticNatRule : firewallStaticNatRules) {
            s_logger.trace("Marking static nat rule " + firewallStaticNatRule + " with Revoke state");
            IpAddress ip = _ipAddressDao.findById(firewallStaticNatRule.getSourceIpAddressId());
            FirewallRuleVO ruleVO = _firewallDao.findById(firewallStaticNatRule.getId());

            if (ip == null || !ip.isOneToOneNat() || ip.getAssociatedWithVmId() == null) {
                throw new InvalidParameterValueException("Source ip address of the rule id=" + firewallStaticNatRule.getId() + " is not static nat enabled");
            }

            String dstIp = getIpInNetwork(ip.getAssociatedWithVmId(), firewallStaticNatRule.getNetworkId());
            ruleVO.setState(FirewallRule.State.Revoke);
            staticNatRules.add(new StaticNatRuleImpl(ruleVO, dstIp));
        }

        try {
            if (!_firewallMgr.applyRules(staticNatRules, true, false)) {
                s_logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // remove all LB rules for the network
        List<LoadBalancerVO> lbs = _lbDao.listByNetworkId(networkId);
        List<LoadBalancingRule> lbRules = new ArrayList<LoadBalancingRule>();
        for (LoadBalancerVO lb : lbs) {
            s_logger.trace("Marking lb rule " + lb + " with Revoke state");
            lb.setState(FirewallRule.State.Revoke);
            List<LbDestination> dstList = _lbMgr.getExistingDestinations(lb.getId());
            List<LbStickinessPolicy> policyList = _lbMgr.getStickinessPolicies(lb.getId());
            // mark all destination with revoke state
            for (LbDestination dst : dstList) {
                s_logger.trace("Marking lb destination " + dst + " with Revoke state");
                dst.setRevoked(true);
            }

            LoadBalancingRule loadBalancing = new LoadBalancingRule(lb, dstList, policyList);
            lbRules.add(loadBalancing);
        }

        try {
            if (!_firewallMgr.applyRules(lbRules, true, false)) {
                s_logger.warn("Failed to cleanup lb rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup lb rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // revoke all firewall rules for the network w/o applying them on the DB
        List<FirewallRuleVO> firewallRules = _firewallDao.listByNetworkAndPurpose(networkId, Purpose.Firewall);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + firewallRules.size() + " firewall rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        for (FirewallRuleVO firewallRule : firewallRules) {
            s_logger.trace("Marking firewall rule " + firewallRule + " with Revoke state");
            firewallRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(firewallRules, true, false)) {
                s_logger.warn("Failed to cleanup firewall rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup firewall rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }
        
        //revoke all Network ACLs for the network w/o applying them in the DB
        List<FirewallRuleVO> networkACLs = _firewallDao.listByNetworkAndPurpose(networkId, Purpose.NetworkACL);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + networkACLs.size() + " Network ACLs for network id=" + networkId +
                    " as a part of shutdownNetworkRules");
        }

        for (FirewallRuleVO networkACL : networkACLs) {
            s_logger.trace("Marking network ACL " + networkACL + " with Revoke state");
            networkACL.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(networkACLs, true, false)) {
                s_logger.warn("Failed to cleanup network ACLs as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup network ACLs as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }
        
        //release all static nats for the network
        if (!_rulesMgr.applyStaticNatForNetwork(networkId, false, caller, true)) {
            s_logger.warn("Failed to disable static nats as part of shutdownNetworkRules for network id " + networkId);
            success = false;
        }

        // Get all ip addresses, mark as releasing and release them on the backend
        Network network = getNetwork(networkId);
        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(networkId, null);
        List<PublicIp> publicIpsToRelease = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            for (IPAddressVO userIp : userIps) {
                userIp.setState(State.Releasing);
                PublicIp publicIp = new PublicIp(userIp, _vlanDao.findById(userIp.getVlanId()), NetUtils.createSequenceBasedMacAddress(userIp.getMacAddress()));
                publicIpsToRelease.add(publicIp);
            }
        }

        try {
            if (!applyIpAssociations(network, true, true, publicIpsToRelease)) {
                s_logger.warn("Unable to apply ip address associations for " + network + " as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        return success;
    }

    @Override
    public boolean isSecurityGroupSupportedInNetwork(Network network) {
        if (network.getTrafficType() != TrafficType.Guest) {
            s_logger.trace("Security group can be enabled for Guest networks only; and network " + network + " has a diff traffic type");
            return false;
        }

        Long physicalNetworkId = network.getPhysicalNetworkId();

        // physical network id can be null in Guest Network in Basic zone, so locate the physical network
        if (physicalNetworkId == null) {
            physicalNetworkId = findPhysicalNetworkId(network.getDataCenterId(), null, null);
        }

        return isServiceEnabledInNetwork(physicalNetworkId, network.getId(), Service.SecurityGroup);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_CREATE, eventDescription = "Creating Physical Network TrafficType", create = true)
    public PhysicalNetworkTrafficType addTrafficTypeToPhysicalNetwork(Long physicalNetworkId, String trafficTypeStr, String xenLabel, String kvmLabel, String vmwareLabel, String simulatorLabel, String vlan) {

        // verify input parameters
        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            throw new InvalidParameterValueException("Physical Network id=" + physicalNetworkId + "doesn't exist in the system");
        }

        Networks.TrafficType trafficType = null;
        if (trafficTypeStr != null && !trafficTypeStr.isEmpty()) {
            try {
                trafficType = Networks.TrafficType.valueOf(trafficTypeStr);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve trafficType '" + trafficTypeStr + "' to a supported value");
            }
        }

        if (_pNTrafficTypeDao.isTrafficTypeSupported(physicalNetworkId, trafficType)) {
            throw new CloudRuntimeException("This physical network already supports the traffic type: " + trafficType);
        }
        // For Storage, Control, Management, Public check if the zone has any other physical network with this
        // traffictype already present
        // If yes, we cant add these traffics to one more physical network in the zone.

        if (TrafficType.isSystemNetwork(trafficType) || TrafficType.Public.equals(trafficType) || TrafficType.Storage.equals(trafficType)) {
            if (!_physicalNetworkDao.listByZoneAndTrafficType(network.getDataCenterId(), trafficType).isEmpty()) {
                throw new CloudRuntimeException("Fail to add the traffic type to physical network because Zone already has a physical network with this traffic type: " + trafficType);
            }
        }

        if (TrafficType.Storage.equals(trafficType)) {
            List<SecondaryStorageVmVO> ssvms = _stnwMgr.getSSVMWithNoStorageNetwork(network.getDataCenterId());
            if (!ssvms.isEmpty()) {
                StringBuilder sb = new StringBuilder(
                        "Cannot add "
                                + trafficType
                                + " traffic type as there are below secondary storage vm still running. Please stop them all and add Storage traffic type again, then destory them all to allow CloudStack recreate them with storage network(If you have added storage network ip range)");
                sb.append("SSVMs:");
                for (SecondaryStorageVmVO ssvm : ssvms) {
                    sb.append(ssvm.getInstanceName()).append(":").append(ssvm.getState());
                }
                throw new CloudRuntimeException(sb.toString());
            }
        }

        Transaction txn = Transaction.currentTxn();
        try {
            txn.start();
            // Create the new traffic type in the database
            if (xenLabel == null) {
                xenLabel = getDefaultXenNetworkLabel(trafficType);
            }
            PhysicalNetworkTrafficTypeVO pNetworktrafficType = new PhysicalNetworkTrafficTypeVO(physicalNetworkId, trafficType, xenLabel, kvmLabel, vmwareLabel, simulatorLabel, vlan);
            pNetworktrafficType = _pNTrafficTypeDao.persist(pNetworktrafficType);

            txn.commit();
            return pNetworktrafficType;
        } catch (Exception ex) {
            s_logger.warn("Exception: ", ex);
            throw new CloudRuntimeException("Fail to add a traffic type to physical network");
        }

    }

    private String getDefaultXenNetworkLabel(TrafficType trafficType) {
        String xenLabel = null;
        switch (trafficType) {
        case Public:
            xenLabel = _configDao.getValue(Config.XenPublicNetwork.key());
            break;
        case Guest:
            xenLabel = _configDao.getValue(Config.XenGuestNetwork.key());
            break;
        case Storage:
            xenLabel = _configDao.getValue(Config.XenStorageNetwork1.key());
            break;
        case Management:
            xenLabel = _configDao.getValue(Config.XenPrivateNetwork.key());
            break;
        case Control:
            xenLabel = "cloud_link_local_network";
            break;
        }
        return xenLabel;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_CREATE, eventDescription = "Creating Physical Network TrafficType", async = true)
    public PhysicalNetworkTrafficType getPhysicalNetworkTrafficType(Long id) {
        return _pNTrafficTypeDao.findById(id);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_UPDATE, eventDescription = "Updating physical network TrafficType", async = true)
    public PhysicalNetworkTrafficType updatePhysicalNetworkTrafficType(Long id, String xenLabel, String kvmLabel, String vmwareLabel) {

        PhysicalNetworkTrafficTypeVO trafficType = _pNTrafficTypeDao.findById(id);

        if (trafficType == null) {
            throw new InvalidParameterValueException("Traffic Type with id=" + id + "doesn't exist in the system");
        }

        if (xenLabel != null) {
            if("".equals(xenLabel)){
                xenLabel = null;
            }
            trafficType.setXenNetworkLabel(xenLabel);
        }
        if (kvmLabel != null) {
            if("".equals(kvmLabel)){
                kvmLabel = null;
            }
            trafficType.setKvmNetworkLabel(kvmLabel);
        }
        if (vmwareLabel != null) {
            if("".equals(vmwareLabel)){
                vmwareLabel = null;
            }
            trafficType.setVmwareNetworkLabel(vmwareLabel);
        }
        _pNTrafficTypeDao.update(id, trafficType);

        return trafficType;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_DELETE, eventDescription = "Deleting physical network TrafficType", async = true)
    public boolean deletePhysicalNetworkTrafficType(Long id) {
        PhysicalNetworkTrafficTypeVO trafficType = _pNTrafficTypeDao.findById(id);

        if (trafficType == null) {
            throw new InvalidParameterValueException("Traffic Type with id=" + id + "doesn't exist in the system");
        }

        // check if there are any networks associated to this physical network with this traffic type
        if (TrafficType.Guest.equals(trafficType.getTrafficType())) {
            if (!_networksDao.listByPhysicalNetworkTrafficType(trafficType.getPhysicalNetworkId(), trafficType.getTrafficType()).isEmpty()) {
                throw new CloudRuntimeException("The Traffic Type is not deletable because there are existing networks with this traffic type:" + trafficType.getTrafficType());
            }
        } else if (TrafficType.Storage.equals(trafficType.getTrafficType())) {
            PhysicalNetworkVO pn = _physicalNetworkDao.findById(trafficType.getPhysicalNetworkId());
            if (_stnwMgr.isAnyStorageIpInUseInZone(pn.getDataCenterId())) {
                throw new CloudRuntimeException("The Traffic Type is not deletable because there are still some storage network ip addresses in use:" + trafficType.getTrafficType());
            }
        }
        return _pNTrafficTypeDao.remove(id);
    }

    @Override
    public Pair<List<? extends PhysicalNetworkTrafficType>, Integer> listTrafficTypes(Long physicalNetworkId) {
        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Physical Network with specified id doesn't exist in the system");
            ex.addProxyObject(network, physicalNetworkId, "physicalNetworkId");
            throw ex;
        }

        Pair<List<PhysicalNetworkTrafficTypeVO>, Integer> result = _pNTrafficTypeDao.listAndCountBy(physicalNetworkId);
        return new Pair<List<? extends PhysicalNetworkTrafficType>, Integer>(result.first(), result.second());
    }

    @Override
    public PhysicalNetwork getDefaultPhysicalNetworkByZoneAndTrafficType(long zoneId, TrafficType trafficType) {

        List<PhysicalNetworkVO> networkList = _physicalNetworkDao.listByZoneAndTrafficType(zoneId, trafficType);

        if (networkList.isEmpty()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find the default physical network with traffic=" + trafficType + " in the specified zone id");
            // Since we don't have a DataCenterVO object at our disposal, we just set the table name that the zoneId's corresponding uuid is looked up from, manually.
            ex.addProxyObject("data_center", zoneId, "zoneId");
            throw ex;
        }

        if (networkList.size() > 1) {
            InvalidParameterValueException ex = new InvalidParameterValueException("More than one physical networks exist in zone id=" + zoneId + " with traffic type=" + trafficType);
            ex.addProxyObject("data_center", zoneId, "zoneId");
            throw ex;
        }

        return networkList.get(0);
    }
    
    @Override
    public String getDefaultManagementTrafficLabel(long zoneId, HypervisorType hypervisorType){
        try{
            PhysicalNetwork mgmtPhyNetwork = getDefaultPhysicalNetworkByZoneAndTrafficType(zoneId, TrafficType.Management);
            PhysicalNetworkTrafficTypeVO mgmtTraffic = _pNTrafficTypeDao.findBy(mgmtPhyNetwork.getId(), TrafficType.Management);
            if(mgmtTraffic != null){
                String label = null;
                switch(hypervisorType){
                    case XenServer : label = mgmtTraffic.getXenNetworkLabel(); 
                                     break;
                    case KVM : label = mgmtTraffic.getKvmNetworkLabel();
                               break;
                    case VMware : label = mgmtTraffic.getVmwareNetworkLabel();
                                  break;
                }
                return label;
            }
        }catch(Exception ex){
            if(s_logger.isDebugEnabled()){
                s_logger.debug("Failed to retrive the default label for management traffic:"+"zone: "+ zoneId +" hypervisor: "+hypervisorType +" due to:" + ex.getMessage());
            }
        }
        return null;
    }
    
    @Override
    public String getDefaultStorageTrafficLabel(long zoneId, HypervisorType hypervisorType){
        try{
            PhysicalNetwork storagePhyNetwork = getDefaultPhysicalNetworkByZoneAndTrafficType(zoneId, TrafficType.Storage);
            PhysicalNetworkTrafficTypeVO storageTraffic = _pNTrafficTypeDao.findBy(storagePhyNetwork.getId(), TrafficType.Storage);
            if(storageTraffic != null){
                String label = null;
                switch(hypervisorType){
                    case XenServer : label = storageTraffic.getXenNetworkLabel(); 
                                     break;
                    case KVM : label = storageTraffic.getKvmNetworkLabel();
                               break;
                    case VMware : label = storageTraffic.getVmwareNetworkLabel();
                                  break;
                }
                return label;
            }
        }catch(Exception ex){
            if(s_logger.isDebugEnabled()){
                s_logger.debug("Failed to retrive the default label for storage traffic:"+"zone: "+ zoneId +" hypervisor: "+hypervisorType +" due to:" + ex.getMessage());
            }
        }
        return null;
    }


    @Override
    public boolean processAnswers(long agentId, long seq, Answer[] answers) {
        return false;
    }

    @Override
    public boolean processCommands(long agentId, long seq, Command[] commands) {
        return false;
    }

    @Override
    public AgentControlAnswer processControlCommand(long agentId, AgentControlCommand cmd) {
        return null;
    }

    @Override
    public List<PhysicalNetworkSetupInfo> getPhysicalNetworkInfo(long dcId, HypervisorType hypervisorType) {
        List<PhysicalNetworkSetupInfo> networkInfoList = new ArrayList<PhysicalNetworkSetupInfo>();
        List<PhysicalNetworkVO> physicalNtwkList = _physicalNetworkDao.listByZone(dcId);
        for (PhysicalNetworkVO pNtwk : physicalNtwkList) {
            String publicName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Public, hypervisorType);
            String privateName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Management, hypervisorType);
            String guestName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Guest, hypervisorType);
            String storageName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Storage, hypervisorType);
            // String controlName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Control, hypervisorType);
            PhysicalNetworkSetupInfo info = new PhysicalNetworkSetupInfo();
            info.setPhysicalNetworkId(pNtwk.getId());
            info.setGuestNetworkName(guestName);
            info.setPrivateNetworkName(privateName);
            info.setPublicNetworkName(publicName);
            info.setStorageNetworkName(storageName);
            PhysicalNetworkTrafficTypeVO mgmtTraffic = _pNTrafficTypeDao.findBy(pNtwk.getId(), TrafficType.Management);
            if (mgmtTraffic != null) {
                String vlan = mgmtTraffic.getVlan();
                info.setMgmtVlan(vlan);
            }
            networkInfoList.add(info);
        }
        return networkInfoList;
    }

    @Override
    public void processConnect(HostVO host, StartupCommand cmd, boolean forRebalance) throws ConnectionException {
        if (!(cmd instanceof StartupRoutingCommand)) {
            return;
        }
        long hostId = host.getId();
        StartupRoutingCommand startup = (StartupRoutingCommand) cmd;

        String dataCenter = startup.getDataCenter();

        long dcId = -1;
        DataCenterVO dc = _dcDao.findByName(dataCenter);
        if (dc == null) {
            try {
                dcId = Long.parseLong(dataCenter);
                dc = _dcDao.findById(dcId);
            } catch (final NumberFormatException e) {
            }
        }
        if (dc == null) {
            throw new IllegalArgumentException("Host " + startup.getPrivateIpAddress() + " sent incorrect data center: " + dataCenter);
        }
        dcId = dc.getId();
        HypervisorType hypervisorType = startup.getHypervisorType();

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Host's hypervisorType is: " + hypervisorType);
        }

        List<PhysicalNetworkSetupInfo> networkInfoList = new ArrayList<PhysicalNetworkSetupInfo>();

        // list all physicalnetworks in the zone & for each get the network names
        List<PhysicalNetworkVO> physicalNtwkList = _physicalNetworkDao.listByZone(dcId);
        for (PhysicalNetworkVO pNtwk : physicalNtwkList) {
            String publicName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Public, hypervisorType);
            String privateName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Management, hypervisorType);
            String guestName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Guest, hypervisorType);
            String storageName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Storage, hypervisorType);
            // String controlName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Control, hypervisorType);
            PhysicalNetworkSetupInfo info = new PhysicalNetworkSetupInfo();
            info.setPhysicalNetworkId(pNtwk.getId());
            info.setGuestNetworkName(guestName);
            info.setPrivateNetworkName(privateName);
            info.setPublicNetworkName(publicName);
            info.setStorageNetworkName(storageName);
            PhysicalNetworkTrafficTypeVO mgmtTraffic = _pNTrafficTypeDao.findBy(pNtwk.getId(), TrafficType.Management);
            if (mgmtTraffic != null) {
                String vlan = mgmtTraffic.getVlan();
                info.setMgmtVlan(vlan);
            }
            networkInfoList.add(info);
        }

        // send the names to the agent
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Sending CheckNetworkCommand to check the Network is setup correctly on Agent");
        }
        CheckNetworkCommand nwCmd = new CheckNetworkCommand(networkInfoList);

        CheckNetworkAnswer answer = (CheckNetworkAnswer) _agentMgr.easySend(hostId, nwCmd);

        if (answer == null) {
            s_logger.warn("Unable to get an answer to the CheckNetworkCommand from agent:" + host.getId());
            throw new ConnectionException(true, "Unable to get an answer to the CheckNetworkCommand from agent: " + host.getId());
        }

        if (!answer.getResult()) {
            s_logger.warn("Unable to setup agent " + hostId + " due to " + ((answer != null) ? answer.getDetails() : "return null"));
            String msg = "Incorrect Network setup on agent, Reinitialize agent after network names are setup, details : " + answer.getDetails();
            _alertMgr.sendAlert(AlertManager.ALERT_TYPE_HOST, dcId, host.getPodId(), msg, msg);
            throw new ConnectionException(true, msg);
        } else {
            if (answer.needReconnect()) {
                throw new ConnectionException(false, "Reinitialize agent after network setup.");
            }
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Network setup is correct on Agent");
            }
            return;
        }
    }

    @Override
    public boolean processDisconnect(long agentId, Status state) {
        return false;
    }

    @Override
    public boolean isRecurring() {
        return false;
    }

    @Override
    public int getTimeout() {
        return 0;
    }

    @Override
    public boolean processTimeout(long agentId, long seq) {
        return false;
    }

    private boolean isProviderEnabled(PhysicalNetworkServiceProvider provider) {
        if (provider == null || provider.getState() != PhysicalNetworkServiceProvider.State.Enabled) { // TODO: check
// for other states: Shutdown?
            return false;
        }
        return true;
    }

    @Override
    public boolean isProviderEnabledInPhysicalNetwork(long physicalNetowrkId, String providerName) {
        PhysicalNetworkServiceProviderVO ntwkSvcProvider = _pNSPDao.findByServiceProvider(physicalNetowrkId, providerName);
        if (ntwkSvcProvider == null) {
            s_logger.warn("Unable to find provider " + providerName + " in physical network id=" + physicalNetowrkId);
            return false;
        }
        return isProviderEnabled(ntwkSvcProvider);
    }

    private boolean isServiceEnabledInNetwork(long physicalNetworkId, long networkId, Service service) {
        // check if the service is supported in the network
        if (!areServicesSupportedInNetwork(networkId, service)) {
            s_logger.debug("Service " + service.getName() + " is not supported in the network id=" + networkId);
            return false;
        }

        // get provider for the service and check if all of them are supported
        String provider = _ntwkSrvcDao.getProviderForServiceInNetwork(networkId, service);
        if (!isProviderEnabledInPhysicalNetwork(physicalNetworkId, provider)) {
            s_logger.debug("Provider " + provider + " is not enabled in physical network id=" + physicalNetworkId);
            return false;
        }

        return true;
    }

    @Override
    public String getNetworkTag(HypervisorType hType, Network network) {
        // no network tag for control traffic type
        TrafficType effectiveTrafficType = network.getTrafficType();
        if(hType == HypervisorType.VMware && effectiveTrafficType == TrafficType.Control)
            effectiveTrafficType = TrafficType.Management;
        
        if (effectiveTrafficType == TrafficType.Control) {
            return null;
        }

        Long physicalNetworkId = null;
        if (effectiveTrafficType != TrafficType.Guest) {
            physicalNetworkId = getNonGuestNetworkPhysicalNetworkId(network);
        } else {
            NetworkOffering offering = _configMgr.getNetworkOffering(network.getNetworkOfferingId());
            physicalNetworkId = network.getPhysicalNetworkId();
            if(physicalNetworkId == null){
                physicalNetworkId = findPhysicalNetworkId(network.getDataCenterId(), offering.getTags(), offering.getTrafficType());
            }
        }

        if (physicalNetworkId == null) {
            assert (false) : "Can't get the physical network";
            s_logger.warn("Can't get the physical network");
            return null;
        }

        return _pNTrafficTypeDao.getNetworkTag(physicalNetworkId, effectiveTrafficType, hType);
    }

    protected Long getNonGuestNetworkPhysicalNetworkId(Network network) {
        // no physical network for control traffic type

        // have to remove this sanity check as VMware control network is management network
        // we need to retrieve traffic label information through physical network
/*        
        if (network.getTrafficType() == TrafficType.Control) {
            return null;
        }
*/
        Long physicalNetworkId = network.getPhysicalNetworkId();

        if (physicalNetworkId == null) {
            List<PhysicalNetworkVO> pNtwks = _physicalNetworkDao.listByZone(network.getDataCenterId());
            if (pNtwks.size() == 1) {
                physicalNetworkId = pNtwks.get(0).getId();
            } else {
                // locate physicalNetwork with supported traffic type
                // We can make this assumptions based on the fact that Public/Management/Control traffic types are
                // supported only in one physical network in the zone in 3.0
                for (PhysicalNetworkVO pNtwk : pNtwks) {
                    if (_pNTrafficTypeDao.isTrafficTypeSupported(pNtwk.getId(), network.getTrafficType())) {
                        physicalNetworkId = pNtwk.getId();
                        break;
                    }
                }
            }
        }
        return physicalNetworkId;
    }

    @Override
    public NetworkVO getExclusiveGuestNetwork(long zoneId) {
        List<NetworkVO> networks = _networksDao.listBy(Account.ACCOUNT_ID_SYSTEM, zoneId, GuestType.Shared, TrafficType.Guest);
        if (networks == null || networks.isEmpty()) {
            throw new InvalidParameterValueException("Unable to find network with trafficType " + TrafficType.Guest + " and guestType " + GuestType.Shared + " in zone " + zoneId);
        }

        if (networks.size() > 1) {
            throw new InvalidParameterValueException("Found more than 1 network with trafficType " + TrafficType.Guest + " and guestType " + GuestType.Shared + " in zone " + zoneId);

        }

        return networks.get(0);
    }

    protected PhysicalNetworkServiceProvider addDefaultVirtualRouterToPhysicalNetwork(long physicalNetworkId) {

        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, Network.Provider.VirtualRouter.getName(), null, null);
        // add instance of the provider
        VirtualRouterElement element = (VirtualRouterElement) getElementImplementingProvider(Network.Provider.VirtualRouter.getName());
        if (element == null) {
            throw new CloudRuntimeException("Unable to find the Network Element implementing the VirtualRouter Provider");
        }
        element.addElement(nsp.getId(), VirtualRouterProviderType.VirtualRouter);

        return nsp;
    }
    
    protected PhysicalNetworkServiceProvider addDefaultVpcVirtualRouterToPhysicalNetwork(long physicalNetworkId) {

        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, 
                Network.Provider.VPCVirtualRouter.getName(), null, null);
        // add instance of the provider
        VpcVirtualRouterElement element = (VpcVirtualRouterElement) getElementImplementingProvider(Network.Provider.VPCVirtualRouter.getName());
        if (element == null) {
            throw new CloudRuntimeException("Unable to find the Network Element implementing the VPCVirtualRouter Provider");
        }
        element.addElement(nsp.getId(), VirtualRouterProviderType.VPCVirtualRouter);

        return nsp;
    }

    protected PhysicalNetworkServiceProvider addDefaultSecurityGroupProviderToPhysicalNetwork(long physicalNetworkId) {

        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, 
                Network.Provider.SecurityGroupProvider.getName(), null, null);

        return nsp;
    }

    @Override
    public boolean isNetworkSystem(Network network) {
        NetworkOffering no = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (no.isSystemOnly()) {
            return true;
        } else {
            return false;
        }
    }

    protected Map<String, String> finalizeServicesAndProvidersForNetwork(NetworkOffering offering, Long physicalNetworkId) {
        Map<String, String> svcProviders = new HashMap<String, String>();
        Map<String, List<String>> providerSvcs = new HashMap<String, List<String>>();
        List<NetworkOfferingServiceMapVO> servicesMap = _ntwkOfferingSrvcDao.listByNetworkOfferingId(offering.getId());

        boolean checkPhysicalNetwork = (physicalNetworkId != null) ? true : false;

        for (NetworkOfferingServiceMapVO serviceMap : servicesMap) {
            if (svcProviders.containsKey(serviceMap.getService())) {
                // FIXME - right now we pick up the first provider from the list, need to add more logic based on
                // provider load, etc
                continue;
            }

            String service = serviceMap.getService();
            String provider = serviceMap.getProvider();

            if (provider == null) {
                provider = getDefaultUniqueProviderForService(service).getName();
            }

            // check that provider is supported
            if (checkPhysicalNetwork) {
                if (!_pNSPDao.isServiceProviderEnabled(physicalNetworkId, provider, service)) {
                    throw new UnsupportedServiceException("Provider " + provider + " is either not enabled or doesn't " +
                            "support service " + service + " in physical network id=" + physicalNetworkId);
                }
            }

            svcProviders.put(service, provider);
            List<String> l = providerSvcs.get(provider);
            if (l == null) {
                providerSvcs.put(provider, l = new ArrayList<String>());
            }
            l.add(service);
        }

        return svcProviders;
    }

    @Override
    public Long getPhysicalNetworkId(Network network) {
        if (network.getTrafficType() != TrafficType.Guest) {
            return getNonGuestNetworkPhysicalNetworkId(network);
        }

        Long physicalNetworkId = network.getPhysicalNetworkId();
        NetworkOffering offering = _configMgr.getNetworkOffering(network.getNetworkOfferingId());
        if (physicalNetworkId == null) {
            physicalNetworkId = findPhysicalNetworkId(network.getDataCenterId(), offering.getTags(), offering.getTrafficType());
        }
        return physicalNetworkId;
    }

    @Override
    public boolean getAllowSubdomainAccessGlobal() {
        return _allowSubdomainNetworkAccess;
    }

    private List<Provider> getNetworkProviders(long networkId) {
        List<String> providerNames = _ntwkSrvcDao.getDistinctProviders(networkId);
        List<Provider> providers = new ArrayList<Provider>();
        for (String providerName : providerNames) {
            providers.add(Network.Provider.getProvider(providerName));
        }

        return providers;
    }

    @Override
    public boolean isProviderForNetwork(Provider provider, long networkId) {
        if (_ntwkSrvcDao.isProviderForNetwork(networkId, provider) != null) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean isProviderForNetworkOffering(Provider provider, long networkOfferingId) {
        if (_ntwkOfferingSrvcDao.isProviderForNetworkOffering(networkOfferingId, provider)) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void canProviderSupportServices(Map<Provider, Set<Service>> providersMap) {
        for (Provider provider : providersMap.keySet()) {
            // check if services can be turned off
            NetworkElement element = getElementImplementingProvider(provider.getName());
            if (element == null) {
                throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getName() + "'");
            }

            Set<Service> enabledServices = new HashSet<Service>();
            enabledServices.addAll(providersMap.get(provider));

            if (enabledServices != null && !enabledServices.isEmpty()) {
                if (!element.canEnableIndividualServices()) {
                    Set<Service> requiredServices = new HashSet<Service>();                    
                    requiredServices.addAll(element.getCapabilities().keySet());
                    
                    if (requiredServices.contains(Network.Service.Gateway)) {
                        requiredServices.remove(Network.Service.Gateway);
                    }
                    
                    if (requiredServices.contains(Network.Service.Firewall)) {
                        requiredServices.remove(Network.Service.Firewall);
                    }
                    
                    if (enabledServices.contains(Network.Service.Firewall)) {
                        enabledServices.remove(Network.Service.Firewall);
                    }

                    // exclude gateway service
                    if (enabledServices.size() != requiredServices.size()) {
                        StringBuilder servicesSet = new StringBuilder();

                        for (Service requiredService : requiredServices) {
                            // skip gateway service as we don't allow setting it via API
                            if (requiredService == Service.Gateway) {
                                continue;
                            }
                            servicesSet.append(requiredService.getName() + ", ");
                        }
                        servicesSet.delete(servicesSet.toString().length() - 2, servicesSet.toString().length());

                        throw new InvalidParameterValueException("Cannot enable subset of Services, Please specify the complete list of Services: " + servicesSet.toString() + "  for Service Provider "
                                + provider.getName());
                    }
                }
                List<String> serviceList = new ArrayList<String>();
                for (Service service : enabledServices) {
                    // check if the service is provided by this Provider
                    if (!element.getCapabilities().containsKey(service)) {
                        throw new UnsupportedServiceException(provider.getName() + " Provider cannot provide service " + service.getName());
                    }
                    serviceList.add(service.getName());
                }
                if (!element.verifyServicesCombination(enabledServices)) {
                    throw new UnsupportedServiceException("Provider " + provider.getName() + " doesn't support services combination: " + serviceList);
                }
            }
        }
    }

    @Override
    public boolean canAddDefaultSecurityGroup() {
        String defaultAdding = _configDao.getValue(Config.SecurityGroupDefaultAdding.key());
        return (defaultAdding != null && defaultAdding.equalsIgnoreCase("true"));
    }

    @Override
    public List<Service> listNetworkOfferingServices(long networkOfferingId) {
        List<Service> services = new ArrayList<Service>();
        List<String> servicesStr = _ntwkOfferingSrvcDao.listServicesForNetworkOffering(networkOfferingId);
        for (String serviceStr : servicesStr) {
            services.add(Service.getService(serviceStr));
        }

        return services;
    }

    @Override
    public boolean areServicesEnabledInZone(long zoneId, NetworkOffering offering, List<Service> services) {
        long physicalNtwkId = findPhysicalNetworkId(zoneId, offering.getTags(), offering.getTrafficType());
        boolean result = true;
        List<String> checkedProvider = new ArrayList<String>();
        for (Service service : services) {
            // get all the providers, and check if each provider is enabled
            List<String> providerNames = _ntwkOfferingSrvcDao.listProvidersForServiceForNetworkOffering(offering.getId(), service);
            for (String providerName : providerNames) {
                if (!checkedProvider.contains(providerName)) {
                    result = result && isProviderEnabledInPhysicalNetwork(physicalNtwkId, providerName);
                }
            }
        }

        return result;
    }

    @Override
    public boolean checkIpForService(IPAddressVO userIp, Service service, Long networkId) {
        if (networkId == null) {
            networkId = userIp.getAssociatedWithNetworkId();
        }
        
        NetworkVO network = _networksDao.findById(networkId);
        NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
        if (offering.getGuestType() != GuestType.Isolated) {
            return true;
        }
        PublicIp publicIp = new PublicIp(userIp, _vlanDao.findById(userIp.getVlanId()), NetUtils.createSequenceBasedMacAddress(userIp.getMacAddress()));
        if (!canIpUsedForService(publicIp, service, networkId)) {
            return false;
        }
        if (!offering.isConserveMode()) {
            return canIpUsedForNonConserveService(publicIp, service);
        }
        return true;
    }

    @Override
    public List<Pair<TrafficType, String>> listTrafficTypeImplementor(ListTrafficTypeImplementorsCmd cmd) {
        String type = cmd.getTrafficType();
        List<Pair<TrafficType, String>> results = new ArrayList<Pair<TrafficType, String>>();
        if (type != null) {
            for (NetworkGuru guru : _networkGurus) {
                if (guru.isMyTrafficType(TrafficType.getTrafficType(type))) {
                    results.add(new Pair<TrafficType, String>(TrafficType.getTrafficType(type), guru.getName()));
                    break;
                }
            }
        } else {
            for (NetworkGuru guru : _networkGurus) {
                TrafficType[] allTypes = guru.getSupportedTrafficType();
                for (TrafficType t : allTypes) {
                    results.add(new Pair<TrafficType, String>(t, guru.getName()));
                }
            }
        }

        return results;
    }

    @Override
    public void checkCapabilityForProvider(Set<Provider> providers, Service service, Capability cap, String capValue) {
        for (Provider provider : providers) {
            NetworkElement element = getElementImplementingProvider(provider.getName());
            if (element != null) {
                Map<Service, Map<Capability, String>> elementCapabilities = element.getCapabilities();
                if (elementCapabilities == null || !elementCapabilities.containsKey(service)) {
                    throw new UnsupportedServiceException("Service " + service.getName() + " is not supported by the element=" + element.getName() + " implementing Provider=" + provider.getName());
                }
                Map<Capability, String> serviceCapabilities = elementCapabilities.get(service);
                if (serviceCapabilities == null || serviceCapabilities.isEmpty()) {
                    throw new UnsupportedServiceException("Service " + service.getName() + " doesn't have capabilites for element=" + element.getName() + " implementing Provider=" + provider.getName());
                }

                String value = serviceCapabilities.get(cap);
                if (value == null || value.isEmpty()) {
                    throw new UnsupportedServiceException("Service " + service.getName() + " doesn't have capability " + cap.getName() + " for element=" + element.getName() + " implementing Provider="
                            + provider.getName());
                }

                capValue = capValue.toLowerCase();

                if (!value.contains(capValue)) {
                    throw new UnsupportedServiceException("Service " + service.getName() + " doesn't support value " + capValue + " for capability " + cap.getName() + " for element=" + element.getName()
                            + " implementing Provider=" + provider.getName());
                }
            } else {
                throw new UnsupportedServiceException("Unable to find network element for provider " + provider.getName());
            }
        }
    }

    public IpAddress assignSystemIp(long networkId, Account owner, boolean forElasticLb, boolean forElasticIp) 
            throws InsufficientAddressCapacityException {
        Network guestNetwork = getNetwork(networkId);
        NetworkOffering off = _configMgr.getNetworkOffering(guestNetwork.getNetworkOfferingId());
        IpAddress ip = null;
        if ((off.getElasticLb() && forElasticLb) || (off.getElasticIp() && forElasticIp)) {

            try {
                s_logger.debug("Allocating system IP address for load balancer rule...");
                // allocate ip
                ip = allocateIP(owner, true, guestNetwork.getDataCenterId());
                // apply ip associations
                ip = associateIPToNetwork(ip.getId(), networkId);
            } catch (ResourceAllocationException ex) {
                throw new CloudRuntimeException("Failed to allocate system ip due to ", ex);
            } catch (ConcurrentOperationException ex) {
                throw new CloudRuntimeException("Failed to allocate system lb ip due to ", ex);
            } catch (ResourceUnavailableException ex) {
                throw new CloudRuntimeException("Failed to allocate system lb ip due to ", ex);
            }

            if (ip == null) {
                throw new CloudRuntimeException("Failed to allocate system ip");
            }
        }

        return ip;
    }

    @Override
    public boolean handleSystemIpRelease(IpAddress ip) {
        boolean success = true;
        Long networkId = ip.getAssociatedWithNetworkId();
        if (networkId != null) {
            if (ip.getSystem()) {
                UserContext ctx = UserContext.current();
                if (!disassociatePublicIpAddress(ip.getId(), ctx.getCallerUserId(), ctx.getCaller())) {
                    s_logger.warn("Unable to release system ip address id=" + ip.getId());
                    success = false;
                } else {
                    s_logger.warn("Successfully released system ip address id=" + ip.getId());
                }
            }
        }
        return success;
    }

    @Override
    public void checkNetworkPermissions(Account owner, Network network) {
        // Perform account permission check
        if (network.getGuestType() != Network.GuestType.Shared) {
            List<NetworkVO> networkMap = _networksDao.listBy(owner.getId(), network.getId());
            if (networkMap == null || networkMap.isEmpty()) {
                throw new PermissionDeniedException("Unable to use network with id= " + network.getId() + ", permission denied");
            }
        } else {
            if (!isNetworkAvailableInDomain(network.getId(), owner.getDomainId())) {
                throw new PermissionDeniedException("Shared network id=" + network.getId() + " is not available in domain id=" + owner.getDomainId());
            }
        }
    }

    public void allocateDirectIp(NicProfile nic, DataCenter dc, VirtualMachineProfile<? extends VirtualMachine> vm, Network network, String requestedIp) throws InsufficientVirtualNetworkCapcityException,
            InsufficientAddressCapacityException {
        if (nic.getIp4Address() == null) {
            PublicIp ip = assignPublicIpAddress(dc.getId(), null, vm.getOwner(), VlanType.DirectAttached, network.getId(), requestedIp, false);
            nic.setIp4Address(ip.getAddress().toString());
            nic.setGateway(ip.getGateway());
            nic.setNetmask(ip.getNetmask());
            nic.setIsolationUri(IsolationType.Vlan.toUri(ip.getVlanTag()));
            nic.setBroadcastType(BroadcastDomainType.Vlan);
            nic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(ip.getVlanTag()));
            nic.setFormat(AddressFormat.Ip4);
            nic.setReservationId(String.valueOf(ip.getVlanTag()));
            nic.setMacAddress(ip.getMacAddress());
        }

        nic.setDns1(dc.getDns1());
        nic.setDns2(dc.getDns2());
    }

    @Override
    public String getDefaultPublicTrafficLabel(long dcId, HypervisorType hypervisorType) {
        try {
            PhysicalNetwork publicPhyNetwork = getOnePhysicalNetworkByZoneAndTrafficType(dcId, TrafficType.Public);
            PhysicalNetworkTrafficTypeVO publicTraffic = _pNTrafficTypeDao.findBy(publicPhyNetwork.getId(),
                    TrafficType.Public);
            if (publicTraffic != null) {
                String label = null;
                switch (hypervisorType) {
                case XenServer:
                    label = publicTraffic.getXenNetworkLabel();
                    break;
                case KVM:
                    label = publicTraffic.getKvmNetworkLabel();
                    break;
                case VMware:
                    label = publicTraffic.getVmwareNetworkLabel();
                    break;
                }
                return label;
            }
        } catch (Exception ex) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Failed to retrieve the default label for public traffic." + "zone: " + dcId + " hypervisor: " + hypervisorType + " due to: " + ex.getMessage());
            }
        }
        return null;
    }

    @Override
    public String getDefaultGuestTrafficLabel(long dcId, HypervisorType hypervisorType) {
        try {
            PhysicalNetwork guestPhyNetwork = getOnePhysicalNetworkByZoneAndTrafficType(dcId, TrafficType.Guest);
            PhysicalNetworkTrafficTypeVO guestTraffic = _pNTrafficTypeDao.findBy(guestPhyNetwork.getId(),
                    TrafficType.Guest);
            if (guestTraffic != null) {
                String label = null;
                switch (hypervisorType) {
                case XenServer:
                    label = guestTraffic.getXenNetworkLabel();
                    break;
                case KVM:
                    label = guestTraffic.getKvmNetworkLabel();
                    break;
                case VMware:
                    label = guestTraffic.getVmwareNetworkLabel();
                    break;
                }
                return label;
            }
        } catch (Exception ex) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Failed to retrive the default label for management traffic:" + "zone: " + dcId + 
                        " hypervisor: " + hypervisorType + " due to:" + ex.getMessage());
            }
        }
        return null;
    }

    private PhysicalNetwork getOnePhysicalNetworkByZoneAndTrafficType(long zoneId, TrafficType trafficType) {
        List<PhysicalNetworkVO> networkList = _physicalNetworkDao.listByZoneAndTrafficType(zoneId, trafficType);

        if (networkList.isEmpty()) {
            throw new InvalidParameterValueException("Unable to find the default physical network with traffic="
                    + trafficType + " in zone id=" + zoneId + ". ");
        }

        if (networkList.size() > 1) {
            s_logger.info("More than one physical networks exist in zone id=" + zoneId + " with traffic type="
                    + trafficType + ". ");
        }

        return networkList.get(0);
    }
    
    @Override
    public List<? extends Network> listNetworksByVpc(long vpcId) {
        return _networksDao.listByVpc(vpcId);
    }
    
    @Override
    public String getDefaultNetworkDomain() {
        return _networkDomain;
    }
    
    @Override
    public List<Provider> getNtwkOffDistinctProviders(long ntkwOffId) {
        List<String> providerNames = _ntwkOfferingSrvcDao.getDistinctProviders(ntkwOffId);
        List<Provider> providers = new ArrayList<Provider>();
        for (String providerName : providerNames) {
            providers.add(Network.Provider.getProvider(providerName));
        }

        return providers;
    }

    @Override
    public boolean isVmPartOfNetwork(long vmId, long ntwkId) {
        if (_nicDao.findNonReleasedByInstanceIdAndNetworkId(ntwkId, vmId) != null) {
            return true;
        }
        return false;
    }
    
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_ASSIGN, eventDescription = "associating Ip", async = true)
    public IpAddress associateIPToNetwork(long ipId, long networkId) throws InsufficientAddressCapacityException,
    ResourceAllocationException, ResourceUnavailableException, ConcurrentOperationException {

        Network network = _networksDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Invalid network id is given");
        }

        if (network.getVpcId() != null) {
            throw new InvalidParameterValueException("Can't assign ip to the network directly when network belongs" +
                    " to VPC.Specify vpcId to associate ip address to VPC");
        }
        return associateIPToGuestNetwork(ipId, networkId, true);
        
    }
    

    @Override @DB
    public Network createPrivateNetwork(String networkName, String displayText, long physicalNetworkId, 
            String vlan, String startIp, String endIp, String gateway, String netmask, long networkOwnerId, Long vpcId) 
                    throws ResourceAllocationException, ConcurrentOperationException, InsufficientCapacityException {
        
        Account owner = _accountMgr.getAccount(networkOwnerId);
        
        // Get system network offeirng
        NetworkOfferingVO ntwkOff = _systemNetworks.get(NetworkOffering.SystemPrivateGatewayNetworkOffering);
        
        // Validate physical network
        PhysicalNetwork pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
        if (pNtwk == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find a physical network" +
                    " having the given id");
            ex.addProxyObject("physical_network", physicalNetworkId, "physicalNetworkId");
            throw ex;
        }
        
        // VALIDATE IP INFO
        // if end ip is not specified, default it to startIp
        if (!NetUtils.isValidIp(startIp)) {
            throw new InvalidParameterValueException("Invalid format for the startIp parameter");
        }
        if (endIp == null) {
            endIp = startIp;
        } else if (!NetUtils.isValidIp(endIp)) {
            throw new InvalidParameterValueException("Invalid format for the endIp parameter");
        }

        String cidr = null;
        if (!NetUtils.isValidIp(gateway)) {
            throw new InvalidParameterValueException("Invalid gateway");
        }
        if (!NetUtils.isValidNetmask(netmask)) {
            throw new InvalidParameterValueException("Invalid netmask");
        }

        cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);
        
        
        Transaction txn = Transaction.currentTxn();
        txn.start();
        
        //lock datacenter as we need to get mac address seq from there
        DataCenterVO dc = _dcDao.lockRow(pNtwk.getDataCenterId(), true);
        
        //check if we need to create guest network
        Network privateNetwork = _networksDao.getPrivateNetwork(BroadcastDomainType.Vlan.toUri(vlan).toString(), cidr,
                networkOwnerId, pNtwk.getDataCenterId());
        if (privateNetwork == null) {
            //create Guest network
            privateNetwork = createGuestNetwork(ntwkOff.getId(), networkName, displayText, gateway, cidr, vlan, 
                    null, owner, null, pNtwk, pNtwk.getDataCenterId(), ACLType.Account, null, null);
            s_logger.debug("Created private network " + privateNetwork);
        } else {
            s_logger.debug("Private network already exists: " + privateNetwork);
        }
        
        //add entry to private_ip_address table
        PrivateIpVO privateIp = _privateIpDao.findByIpAndSourceNetworkId(privateNetwork.getId(), startIp);
        if (privateIp != null) {
            throw new InvalidParameterValueException("Private ip address " + startIp + " already used for private gateway" +
                    " in zone " + _configMgr.getZone(pNtwk.getDataCenterId()).getName());
        }
        
        Long mac = dc.getMacAddress();
        Long nextMac = mac + 1;
        dc.setMacAddress(nextMac);

        privateIp = new PrivateIpVO(startIp, privateNetwork.getId(), nextMac, vpcId);
        _privateIpDao.persist(privateIp);
        
        _dcDao.update(dc.getId(), dc);
        
        txn.commit();
        s_logger.debug("Private network " + privateNetwork + " is created");

        return privateNetwork;
    }
    
    @Override
    public boolean setupDns(Network network, Provider provider) {
        boolean dnsProvided = isProviderSupportServiceInNetwork(network.getId(), Service.Dns, provider );
        boolean dhcpProvided =isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, 
                provider);
        
        boolean setupDns = dnsProvided || dhcpProvided;
        return setupDns;
    }
    

    @Override
    public List<? extends PhysicalNetwork> getPhysicalNtwksSupportingTrafficType(long zoneId, TrafficType trafficType) {
        
        List<? extends PhysicalNetwork> pNtwks = _physicalNetworkDao.listByZone(zoneId);
        
        Iterator<? extends PhysicalNetwork> it = pNtwks.iterator();
        while (it.hasNext()) {
            PhysicalNetwork pNtwk = it.next();
            if (!_pNTrafficTypeDao.isTrafficTypeSupported(pNtwk.getId(), trafficType)) {
                it.remove();
            }
        }
        return pNtwks;
    }
    
    @Override
    public boolean isPrivateGateway(Nic guestNic) {
        Network network = getNetwork(guestNic.getNetworkId());
        if (network.getTrafficType() != TrafficType.Guest || network.getNetworkOfferingId() != _privateOfferingId.longValue()) {
            return false;
        }
        return true;
    }
    
    @Override
    public NicProfile createNicForVm(Network network, NicProfile requested, ReservationContext context,
            VirtualMachineProfileImpl<VMInstanceVO> vmProfile, boolean prepare)
            throws InsufficientVirtualNetworkCapcityException, InsufficientAddressCapacityException, 
            ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        
        VirtualMachine vm = vmProfile.getVirtualMachine();
        NetworkVO networkVO = _networksDao.findById(network.getId());
        DataCenter dc = _configMgr.getZone(network.getDataCenterId());
        Host host = _hostDao.findById(vm.getHostId()); 
        DeployDestination dest = new DeployDestination(dc, null, null, host);
        
        NicProfile nic = getNicProfileForVm(network, requested, vm);
        
        //1) allocate nic (if needed)
        if (nic == null) {
            int deviceId = _nicDao.countNics(vm.getId());
            
            nic = allocateNic(requested, network, false, 
                    deviceId, vmProfile).first();
            
            if (nic == null) {
                throw new CloudRuntimeException("Failed to allocate nic for vm " + vm + " in network " + network);
            }
            
            s_logger.debug("Nic is allocated successfully for vm " + vm + " in network " + network); 
        }
        
        //2) prepare nic
        if (prepare) {
            nic = prepareNic(vmProfile, dest, context, nic.getId(), networkVO);
            s_logger.debug("Nic is prepared successfully for vm " + vm + " in network " + network);
        }
        
        return nic;
    }

    private NicProfile getNicProfileForVm(Network network, NicProfile requested, VirtualMachine vm) {
        NicProfile nic = null;
        if (requested != null && requested.getBroadCastUri() != null) {
            String broadcastUri = requested.getBroadCastUri().toString();
            String ipAddress = requested.getIp4Address();
            NicVO nicVO = _nicDao.findByNetworkIdInstanceIdAndBroadcastUri(network.getId(), vm.getId(), broadcastUri);
            if (nicVO != null) {
                if (ipAddress == null || nicVO.getIp4Address().equals(ipAddress)) {
                    nic = getNicProfile(vm, network.getId(), broadcastUri);
                }
            }
        } else {
            NicVO nicVO = _nicDao.findByInstanceIdAndNetworkId(network.getId(), vm.getId());
            if (nicVO != null) {
                nic = getNicProfile(vm, network.getId(), null);
            }
        }
        return nic;
    }
    

    @Override
    public int getNetworkLockTimeout() {
        return _networkLockTimeout;
    }

}
