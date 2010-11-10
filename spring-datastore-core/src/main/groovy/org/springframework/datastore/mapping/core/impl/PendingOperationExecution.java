/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.datastore.mapping.core.impl;

import java.util.List;

/**
 * Provides a default implementation to execute a pending operation
 * 
 * @author Graeme Rocher
 *
 */
public class PendingOperationExecution {
	
	public static void executePendingInsert(PendingOperation pendingInsert) {
		List<PendingOperation> preOperations = pendingInsert.getPreOperations();
		for (PendingOperation preOperation : preOperations) {
			preOperation.run();
		}
		pendingInsert.run();
		List<PendingOperation> cascadeOperations = pendingInsert.getCascadeOperations();
		for (PendingOperation cascadeOperation : cascadeOperations) {
			cascadeOperation.run();
		}
	}

}
