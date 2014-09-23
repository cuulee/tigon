/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tigon.sql.util;

import co.cask.tigon.sql.conf.Constants;
import co.cask.tigon.sql.flowlet.GDATFieldType;
import co.cask.tigon.sql.flowlet.GDATSlidingWindowAttribute;
import co.cask.tigon.sql.flowlet.StreamSchema;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * This is a utility class to get meta information from files generated by the SQL Compiler
 *
 *  Sample qtree.xml - Contains meta information about LFTA & HFTA.
 *
       <QueryNodes>
       <HFTA name='sum1'>
         <HFTAProperty1 />
         <HFTAProperty2 />
          .
          .
         <Field name='timestamp' pos='0' type='INT' mods='INCREASING '  />
         <Field name='s' pos='1' type='FLOAT'  />
          .
          .
       </HFTA>
       <HFTA name='sum2'>
         <HFTAProperty1 />
         <HFTAProperty2 />
       </HFTA>
       <HFTA name='sumOut'>

       </HFTA>
        .
        .
        .
       <LFTA name='_sum1_localhost_intInput1' >
         <LFTAProperty1 />
         <LFTAProperty2 />
         .
         .
         <Field name='timestamp' pos='0' type='INT' mods='INCREASING '  />
         <Field name='s' pos='1' type='FLOAT'  />
         .
         .
       </LFTA>
       <LFTA name='_sum1_localhost_intInput2' >
         <LFTAProperty1 />
         <LFTAProperty2 />
       </LFTA>
        .
        .
        .
       </QueryNodes>
 *
 *
 * output_spec.cfg - Contains a list of all the query names whose results will be shared with the end-user
 */
public class MetaInformationParser {
  //Type casting map from SQL compiler format to Java format
  private static final Map<String, String> dataTypeCastMap = Maps.newHashMap();
  private static final List<String> schemaNames = Lists.newArrayList();

  static {
    dataTypeCastMap.put("int", "int");
    dataTypeCastMap.put("uint", "int");
    dataTypeCastMap.put("ushort", "int");
    dataTypeCastMap.put("bool", "int");
    dataTypeCastMap.put("llong", "llong");
    dataTypeCastMap.put("ullong", "llong");
    dataTypeCastMap.put("float", "float");
    dataTypeCastMap.put("v_str", "v_str");
    dataTypeCastMap.put("string", "v_str");
    dataTypeCastMap.put("vstring", "v_str");
  }

  private static GDATFieldType getGDATFieldType(String fieldName) {
    return GDATFieldType.getGDATFieldType(dataTypeCastMap.get(fieldName));
  }

  /**
   * This method returns the number of HFTA processes to be instantiated by parsing the qtree.xml file
   * @param fileLocation Directory that contains the qtree.xml file
   * @return The number of HFTA processes to be instantiated
   * @throws IOException
   */
  public static int getHFTACount(File fileLocation) {
    Document qtree = getQTree(fileLocation);
    int count;
    count = qtree.getElementsByTagName("HFTA").getLength();
    return count;
  }

  /**
   * This function generates the {@link co.cask.tigon.sql.flowlet.StreamSchema} for each query specified by the user
   * @param fileLocation Directory that contains the qtree.xml file & the output_spec.cfg
   * @return A map of query names and the associated {@link co.cask.tigon.sql.flowlet.StreamSchema}
   * @throws IOException
   */
  public static Map<String, StreamSchema> getSchemaMap(File fileLocation) throws IOException {
    FileInputStream fis = new FileInputStream(new File(fileLocation, Constants.OUTPUT_SPEC));
    BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
    String line;
    while ((line = reader.readLine()) != null) {
      schemaNames.add(line.split(",")[0]);
    }
    fis.close();
    Document qtree = getQTree(fileLocation);
    NodeList lfta = qtree.getElementsByTagName("LFTA");
    NodeList hfta = qtree.getElementsByTagName("HFTA");
    Map<String, StreamSchema> schemaMap = Maps.newHashMap();
    addSchema(lfta, schemaMap);
    addSchema(hfta, schemaMap);
    return schemaMap;
  }

  private static Document getQTree(File fileLocation) {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    Document qtree;
    try {
      DocumentBuilder documentBuilder = factory.newDocumentBuilder();
      qtree = documentBuilder.parse(new File(fileLocation, Constants.QTREE));
    } catch (Exception e) {
      throw new RuntimeException("Error parsing qtree.xml", e);
    }
    return qtree;
  }

  private static void addSchema(NodeList nodeList, Map<String, StreamSchema> schemaMap) {
    for (int i = 0; i < nodeList.getLength(); i++) {
      NodeList childNodes = nodeList.item(i).getChildNodes();
      StreamSchema.Builder builder = new StreamSchema.Builder();
      if (!schemaNames.contains(((Element) nodeList.item(i)).getAttribute("name"))) {
        continue;
      }
      for (int j = 0; j < childNodes.getLength(); j++) {
        if (childNodes.item(j).getNodeName().equals("Field")) {
          if (((Element) childNodes.item(j)).hasAttribute("mods")) {
            if (((Element) childNodes.item(j)).getAttribute("mods").toLowerCase().contains("increasing")) {
              builder.addField(((Element) childNodes.item(j)).getAttribute("name"),
                               getGDATFieldType(((Element) childNodes.item(j)).getAttribute("type").toLowerCase()),
                               GDATSlidingWindowAttribute.INCREASING);
            } else if (((Element) childNodes.item(j)).getAttribute("mods").toLowerCase().contains("decreasing")) {
              builder.addField(((Element) childNodes.item(j)).getAttribute("name"),
                               getGDATFieldType(((Element) childNodes.item(j)).getAttribute("type").toLowerCase()),
                               GDATSlidingWindowAttribute.DECREASING);
            } else {
              builder.addField(((Element) childNodes.item(j)).getAttribute("name"),
                               getGDATFieldType(((Element) childNodes.item(j)).getAttribute("type").toLowerCase()),
                               GDATSlidingWindowAttribute.NONE);
            }
          } else {
            builder.addField(((Element) childNodes.item(j)).getAttribute("name"),
                             getGDATFieldType(((Element) childNodes.item(j)).getAttribute("type").toLowerCase()));
          }
        }
      }
      schemaMap.put(((Element) nodeList.item(i)).getAttribute("name"), builder.build());
    }
  }
}
