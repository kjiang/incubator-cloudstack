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
package com.cloud.api.commands;

import java.util.List;

import org.apache.log4j.Logger;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseCmd;
import com.cloud.api.IdentityMapper;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.StoragePoolResponse;
import com.cloud.storage.StoragePool;
import com.cloud.user.Account;

@Implementation(description="Updates a storage pool.", responseObject=StoragePoolResponse.class, since="3.0.0")
public class UpdateStoragePoolCmd extends BaseCmd {
    public static final Logger s_logger = Logger.getLogger(UpdateStoragePoolCmd.class.getName());

    private static final String s_name = "updatestoragepoolresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @IdentityMapper(entityTableName="storage_pool")
    @Parameter(name=ApiConstants.ID, type=CommandType.LONG, required=true, description="the Id of the storage pool")
    private Long id;

    @Parameter(name=ApiConstants.TAGS, type=CommandType.LIST, collectionType=CommandType.STRING, description="comma-separated list of tags for the storage pool")
    private List<String> tags;
    

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public List<String> getTags() {
        return tags;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }
    
    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
    
    @Override
    public void execute(){
        StoragePool result = _storageService.updateStoragePool(this);
        if (result != null){
            StoragePoolResponse response = _responseGenerator.createStoragePoolResponse(result);
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to update storage pool");
        }
    }
}
