/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.manager;

import org.apache.cloudstack.platform.subsystem.api.storage.DataObjectBackupStorageOperationState.Event;
import org.apache.cloudstack.platform.subsystem.api.storage.DataStore;

import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.utils.fsm.NoTransitionException;

public class BackupStorageManagerImpl implements BackupStorageManager {

	public boolean contains(Volume vol) {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean contains(Snapshot snapshot) {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean contains(VirtualMachineTemplate template) {
		// TODO Auto-generated method stub
		return false;
	}

	public DataStore getBackupDataStore(Volume vol) {
		// TODO Auto-generated method stub
		return null;
	}

	public DataStore getBackupDataStore(Snapshot snapshot) {
		// TODO Auto-generated method stub
		return null;
	}

	public DataStore getBackupDataStore(VirtualMachineTemplate template) {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean updateOperationState(Volume vol, Event event) throws NoTransitionException {
		// TODO Auto-generated method stub
		return false;
	}

}