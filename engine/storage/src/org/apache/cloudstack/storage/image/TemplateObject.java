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
package org.apache.cloudstack.storage.image;

import org.apache.cloudstack.engine.subsystem.api.storage.disktype.VolumeDiskType;
import org.apache.cloudstack.engine.subsystem.api.storage.disktype.VolumeDiskTypeHelper;
import org.apache.cloudstack.storage.image.db.ImageDataVO;
import org.apache.cloudstack.storage.image.store.ImageDataStoreInfo;

public class TemplateObject implements TemplateInfo {
	private ImageDataVO imageVO;
	public TemplateObject(ImageDataVO template) {
		this.imageVO = template;
	}
	@Override
	public ImageDataStoreInfo getImageDataStore() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public long getId() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public VolumeDiskType getDiskType() {
		return VolumeDiskTypeHelper.getDiskType(imageVO.getFormat());
	}
}