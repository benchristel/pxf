package org.greenplum.pxf.plugins.hbase;

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


import org.greenplum.pxf.api.BadRecordException;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.plugins.hbase.utilities.HBaseTupleDescription;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

@RunWith(PowerMockRunner.class)
@PrepareForTest({HBaseResolver.class})
public class HBaseResolverTest {
    RequestContext requestContext;
    HBaseTupleDescription tupleDesc;

    @Test
    /*
	 * Test construction of HBaseResolver.
	 *
	 * HBaseResolver is created and then HBaseTupleDescription
	 * creation is verified
	 */
    public void construction() throws Exception {
        requestContext = mock(RequestContext.class);
        tupleDesc = mock(HBaseTupleDescription.class);
        PowerMockito.whenNew(HBaseTupleDescription.class).withArguments(requestContext).thenReturn(tupleDesc);

        HBaseResolver resolver = new HBaseResolver();
        resolver.initialize(requestContext);

        PowerMockito.verifyNew(HBaseTupleDescription.class).withArguments(requestContext);
    }

    @Test
	/*
	 * Test the convertToJavaObject method
	 */
    public void testConvertToJavaObject() throws Exception {
        Object result;

        requestContext = mock(RequestContext.class);
        tupleDesc = mock(HBaseTupleDescription.class);
        PowerMockito.whenNew(HBaseTupleDescription.class).withArguments(requestContext).thenReturn(tupleDesc);

        HBaseResolver resolver = new HBaseResolver();
        resolver.initialize(requestContext);

        /*
		 * Supported type, No value.
		 * Should successfully return Null.
		 */
        result = resolver.convertToJavaObject(20, "bigint", null);
        assertNull(result);

		/*
		 * Supported type, With value
		 * Should successfully return a Java Object that holds original value
		 */
        result = resolver.convertToJavaObject(20, "bigint", "1234".getBytes());
        assertEquals(((Long) result).longValue(), 1234L);

		/*
		 * Supported type, Invalid value
		 * Should throw a BadRecordException, with detailed explanation.
		 */
        try {
            result = resolver.convertToJavaObject(20, "bigint", "not_a_numeral".getBytes());
            fail("Supported type, Invalid value should throw an exception");
        } catch (BadRecordException e) {
            assertEquals("Error converting value 'not_a_numeral' to type bigint. (original error: For input string: \"not_a_numeral\")", e.getMessage());
        } catch (Exception e) {
            fail("Supported type, Invalid value expected to catch a BadRecordException, caught Exception");
        }

		/*
		 * Unsupported type
		 * Should throw an Exception, indicating the name of the unsupported type
		 */
        try {
            result = resolver.convertToJavaObject(600, "point", "[1,1]".getBytes());
            fail("Unsupported data type should throw exception");
        } catch (Exception e) {
            assertEquals("Unsupported data type point", e.getMessage());
        }

    }
}
