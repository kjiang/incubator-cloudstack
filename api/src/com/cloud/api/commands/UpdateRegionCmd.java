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

import org.apache.log4j.Logger;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseCmd;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.RegionResponse;
import com.cloud.region.Region;
import com.cloud.user.Account;

@Implementation(description="Updates a region", responseObject=RegionResponse.class)
public class UpdateRegionCmd extends BaseCmd {
    public static final Logger s_logger = Logger.getLogger(UpdateRegionCmd.class.getName());
    private static final String s_name = "updateregionresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name=ApiConstants.ID, type=CommandType.INTEGER, required=true, description="ID of region to update")
    private Integer id;

    @Parameter(name=ApiConstants.NAME, type=CommandType.STRING, description="updates region with this name")
    private String regionName;
    
    @Parameter(name=ApiConstants.END_POINT, type=CommandType.STRING, description="updates region with this end point")
    private String endPoint;

    @Parameter(name=ApiConstants.API_KEY, type=CommandType.STRING, description="API key")
    private String apiKey;
    
    @Parameter(name=ApiConstants.SECRET_KEY, type=CommandType.STRING, description="Secret Key")
    private String secretKey;
    
    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Integer getId() {
        return id;
    }

    public String getRegionName() {
        return regionName;
    }
    
    public String getEndPoint() {
        return endPoint;
    }

    public String getApiKey() {
        return apiKey;
    }
    
    public String getSecretKey() {
        return secretKey;
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
    	Region region = _regionService.updateRegion(getId(), getRegionName(), getEndPoint(), getApiKey(), getSecretKey());
    	if (region != null) {
    		RegionResponse response = _responseGenerator.createRegionResponse(region);
    		response.setResponseName(getCommandName());
    		this.setResponseObject(response);
    	} else {
    		throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to update Region");
    	}
    }
}