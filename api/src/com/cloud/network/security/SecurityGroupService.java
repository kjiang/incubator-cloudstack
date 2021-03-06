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
package com.cloud.network.security;

import java.util.List;

import com.cloud.api.commands.AuthorizeSecurityGroupEgressCmd;
import com.cloud.api.commands.AuthorizeSecurityGroupIngressCmd;
import com.cloud.api.commands.CreateSecurityGroupCmd;
import com.cloud.api.commands.DeleteSecurityGroupCmd;
import com.cloud.api.commands.ListSecurityGroupsCmd;
import com.cloud.api.commands.RevokeSecurityGroupEgressCmd;
import com.cloud.api.commands.RevokeSecurityGroupIngressCmd;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceInUseException;

public interface SecurityGroupService {
    /**
     * Create a network group with the given name and description
     * @param command the command specifying the name and description
     * @return the created security group if successful, null otherwise
     */
    public SecurityGroup createSecurityGroup(CreateSecurityGroupCmd command) throws PermissionDeniedException, InvalidParameterValueException;
    boolean revokeSecurityGroupIngress(RevokeSecurityGroupIngressCmd cmd);
    boolean revokeSecurityGroupEgress(RevokeSecurityGroupEgressCmd cmd);
    
    boolean deleteSecurityGroup(DeleteSecurityGroupCmd cmd) throws ResourceInUseException;

    /**
     * Search for security groups and associated ingress rules for the given account, domain, group name, and/or keyword.
     * The search terms are specified in the search criteria.
     * @return the list of security groups and associated ingress rules
     */
    public List<? extends SecurityGroupRules> searchForSecurityGroupRules(ListSecurityGroupsCmd cmd) throws PermissionDeniedException, InvalidParameterValueException;

    public List<? extends SecurityRule> authorizeSecurityGroupIngress(AuthorizeSecurityGroupIngressCmd cmd);
    
    public List<? extends SecurityRule> authorizeSecurityGroupEgress(AuthorizeSecurityGroupEgressCmd cmd);

}
