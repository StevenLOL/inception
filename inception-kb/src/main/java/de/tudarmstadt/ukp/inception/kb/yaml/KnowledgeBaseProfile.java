/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.kb.yaml;

import java.io.Serializable;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

public class KnowledgeBaseProfile implements Serializable
{

    private static final long serialVersionUID = -2684575269500649910L;

    @JsonProperty("name")
    private String name;
    
    @JsonProperty("sparql-url")
    private String sparqlUrl;
    
    @JsonProperty("mapping")
    private KnowledgeBaseMapping mapping;

    public String getName()
    {
        return name;
    }

    public void setName(String aName)
    {
        name = aName;
    }

    public String getSparqlUrl()
    {
        return sparqlUrl;
    }

    public void setSparqlUrl(String aSparqlUrl)
    {
        sparqlUrl = aSparqlUrl;
    }

    public KnowledgeBaseMapping getMapping()
    {
        return mapping;
    }

    public void setMapping(KnowledgeBaseMapping aMapping)
    {
        mapping = aMapping;
    }
    
    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        KnowledgeBaseProfile that = (KnowledgeBaseProfile) o;
        return Objects.equals(name, that.name) && Objects.equals(sparqlUrl, that.sparqlUrl)
                && Objects.equals(mapping, that.mapping);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(name, sparqlUrl, mapping);
    }
}
