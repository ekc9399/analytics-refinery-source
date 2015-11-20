/**
 * Copyright (C) 2015 Wikimedia Foundation
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wikimedia.analytics.refinery.core;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

import junitparams.FileParameters;
import junitparams.JUnitParamsRunner;
import junitparams.mappers.CsvWithHeaderMapper;

@RunWith(JUnitParamsRunner.class)
public class TestSearchQuery {

    @Test
    @FileParameters(
            value = "src/test/resources/search_query_categorizer_test_data.csv",
            mapper = CsvWithHeaderMapper.class
    )

    public void testSearchQueryClassify(
            String test_description,
            String query,
            String expected_output
    ) {
        SearchQuery query_inst = SearchQuery.getInstance();

        assertEquals(
                test_description,
                expected_output,
                query_inst.deconstructSearchQuery(query)
        );
    }

}
