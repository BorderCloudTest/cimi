/*
 Copyright (c) 2012 Centre National de la Recherche Scientifique (CNRS).

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package eu.stratuslab.cimi;

import java.io.StringReader;

import org.junit.Test;

import eu.stratuslab.cimi.ParseException;
import eu.stratuslab.cimi.SimpleNode;

public class CIMIFilterParserTest {
    
    private ASTFilter parseFilter(String filter) throws ParseException {
        CIMIFilterParser parser = new CIMIFilterParser(new StringReader(filter));
        return parser.filter();
    }
    
    @Test
	public void testValidFilters() throws ParseException {
        
        // Check empty statement.
        SimpleNode node = parseFilter("alpha=3 & beta=4");
        node.dump(">  ");
        
    }
    
}
