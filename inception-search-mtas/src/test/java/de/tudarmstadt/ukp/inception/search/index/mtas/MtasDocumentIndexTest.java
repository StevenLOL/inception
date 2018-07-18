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
package de.tudarmstadt.ukp.inception.search.index.mtas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FileUtils;
import org.apache.uima.UIMAException;
import org.apache.uima.jcas.JCas;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;

import de.tudarmstadt.ukp.clarin.webanno.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.clarin.webanno.api.CasStorageService;
import de.tudarmstadt.ukp.clarin.webanno.api.DocumentService;
import de.tudarmstadt.ukp.clarin.webanno.api.ImportExportService;
import de.tudarmstadt.ukp.clarin.webanno.api.ProjectService;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.FeatureSupport;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.FeatureSupportRegistry;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.FeatureSupportRegistryImpl;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.PrimitiveUimaFeatureSupport;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.AnnotationSchemaServiceImpl;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.BackupProperties;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.CasStorageServiceImpl;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.DocumentServiceImpl;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.ImportExportServiceImpl;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.RepositoryProperties;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.initializers.NamedEntityLayerInitializer;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.initializers.PartOfSpeechLayerInitializer;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.initializers.TokenLayerInitializer;
import de.tudarmstadt.ukp.clarin.webanno.curation.storage.CurationDocumentService;
import de.tudarmstadt.ukp.clarin.webanno.curation.storage.CurationDocumentServiceImpl;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.project.ProjectServiceImpl;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDaoImpl;
import de.tudarmstadt.ukp.clarin.webanno.security.model.Role;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.clarin.webanno.support.ApplicationContextProvider;
import de.tudarmstadt.ukp.dkpro.core.io.text.TextReader;
import de.tudarmstadt.ukp.dkpro.core.io.text.TextWriter;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseServiceImpl;
import de.tudarmstadt.ukp.inception.search.FeatureIndexingSupport;
import de.tudarmstadt.ukp.inception.search.FeatureIndexingSupportRegistry;
import de.tudarmstadt.ukp.inception.search.FeatureIndexingSupportRegistryImpl;
import de.tudarmstadt.ukp.inception.search.PrimitiveUimaIndexingSupport;
import de.tudarmstadt.ukp.inception.search.SearchResult;
import de.tudarmstadt.ukp.inception.search.SearchService;
import de.tudarmstadt.ukp.inception.search.SearchServiceImpl;
import de.tudarmstadt.ukp.inception.search.index.PhysicalIndexFactory;
import de.tudarmstadt.ukp.inception.search.index.PhysicalIndexRegistry;
import de.tudarmstadt.ukp.inception.search.index.PhysicalIndexRegistryImpl;
import de.tudarmstadt.ukp.inception.search.scheduling.IndexScheduler;
import net.jodah.concurrentunit.Waiter;

/**
 * The Class MtasDocumentIndexTest.
 */

@RunWith(SpringRunner.class)
@EnableAutoConfiguration
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@EntityScan({ "de.tudarmstadt.ukp.clarin.webanno.model",
        "de.tudarmstadt.ukp.inception.search.model",
        "de.tudarmstadt.ukp.clarin.webanno.security.model" })
@TestPropertySource(locations = "classpath:MtasDocumentIndexTest.properties")
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@DataJpaTest
@Transactional
public class MtasDocumentIndexTest
{
    private final Logger log = LoggerFactory.getLogger(getClass());

    // Number of miliseconds to wait for the indexing to finish. This time must be enough
    // to allow the index be built before the query is made. Otherwise, it could affect the
    // test results. If this happens, a largest value could allow the test to pass.
    private final int WAIT_TIME = 3000;

    private @Autowired UserDao userRepository;

    private @Autowired ProjectService projectService;
    private @Autowired DocumentService documentService;
    private @Autowired SearchService searchService;
    private @Autowired AnnotationSchemaService annotationSchemaService;
    private @Autowired ImportExportService importExportService;

    @Before
    public void setUp()
    {
        userRepository.create(new User("admin", Role.ROLE_ADMIN));
    }

    private void createProject(Project aProject) throws TimeoutException
    {
        Waiter createProjectWaiter = new Waiter();

        // Start thread to create and initialize project
        new Thread(() -> {
            try {
                projectService.createProject(aProject);
                annotationSchemaService.initializeProject(aProject);
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            createProjectWaiter.assertTrue(true);
            createProjectWaiter.resume();
        }).start();

        // Wait for thread to complete
        createProjectWaiter.await();
    }

    private void uploadDocument(Project aProject, String aFileContent,
            SourceDocument aSourceDocument)
        throws TimeoutException
    {
        InputStream fileStream = new ByteArrayInputStream(
                aFileContent.getBytes(StandardCharsets.UTF_8));

        Waiter uploadWaiter = new Waiter();

        // Start thread to upload the document
        new Thread(() -> {
            try {
                documentService.uploadSourceDocument(fileStream, aSourceDocument);

                // Wait some time so that the document can be indexed
                Thread.sleep(WAIT_TIME);

                // Test if the index has been created
                if (searchService.isIndexValid(aProject)) {
                    log.info("**************** Index is valid");
                    uploadWaiter.assertTrue(true);
                }
                else {
                    log.info("**************** Index is NOT valid");
                    uploadWaiter.assertTrue(false);
                }
            }
            catch (UIMAException | IOException | InterruptedException e) {
                e.printStackTrace();
            }

            uploadWaiter.resume();
        }).start();

        // Wait for thread to complete
        uploadWaiter.await();
    }

    private void annotateDocument(Project aProject, User aUser, SourceDocument aSourceDocument)
        throws TimeoutException
    {
        Waiter annotationWaiter = new Waiter();

        // Start thread to annotate the document
        new Thread(() -> {
            try {
                ClassLoader classLoader = getClass().getClassLoader();
                File annotatedFile = new File(classLoader.getResource("galicia.conll").getFile());

                JCas annotationCas = importExportService.importCasFromFile(annotatedFile, aProject,
                        "conll2002");

                AnnotationDocument annotationDocument = documentService
                        .createOrGetAnnotationDocument(aSourceDocument, aUser);

                documentService.writeAnnotationCas(annotationCas, annotationDocument, false);

                // Wait some time so that the document can be indexed
                Thread.sleep(WAIT_TIME);

                // Test if the index has been created
                if (searchService.isIndexValid(aProject)) {
                    log.info("**************** Index is valid");
                    annotationWaiter.assertTrue(true);
                }
                else {
                    log.info("**************** Index is NOT valid");
                    annotationWaiter.assertTrue(false);
                }
            }
            catch (IOException | UIMAException | InterruptedException e) {
                e.printStackTrace();
            }

            annotationWaiter.resume();
        }).start();

        // Wait for thread to complete
        annotationWaiter.await();
    }

    @Test
    public void testSimpleQuery() throws Exception
    {
        Project project = new Project();
        project.setName("TestSimpleQuery");

        createProject(project);

        SourceDocument sourceDocument = new SourceDocument();

        sourceDocument.setName("Simple test");
        sourceDocument.setProject(project);
        sourceDocument.setFormat("text");

        String fileContent = "The capital of Galicia is Santiago de Compostela.";

        uploadDocument(project, fileContent, sourceDocument);

        User user = userRepository.get("admin");

        String query = "Galicia";

        // Execute query
        List<SearchResult> results = (ArrayList<SearchResult>) searchService.query(user, project,
                query);

        // Test results
        SearchResult expectedResult = new SearchResult();
        expectedResult.setDocumentId(sourceDocument.getId());
        expectedResult.setDocumentTitle("test");
        expectedResult.setText("Galicia ");
        expectedResult.setLeftContext("capital of ");
        expectedResult.setRightContext("is ");
        expectedResult.setOffsetStart(15);
        expectedResult.setOffsetEnd(22);
        expectedResult.setTokenStart(3);
        expectedResult.setTokenLength(1);

        assertNotNull(results);
        if (results != null) {
            assertEquals(1, results.size());
            assertEquals(expectedResult, results.get(0));
        }
    }

    @Test
    public void testAnnotationQuery() throws Exception
    {
        Project project = new Project();

        project.setName("TestAnnotationQuery");

        User user = userRepository.get("admin");

        createProject(project);

        SourceDocument sourceDocument = new SourceDocument();

        sourceDocument.setName("Annotation test");
        sourceDocument.setProject(project);
        sourceDocument.setFormat("text");

        String fileContent = "The capital of Galicia is Santiago de Compostela.";

        uploadDocument(project, fileContent, sourceDocument);

        annotateDocument(project, user, sourceDocument);

        String query = "<Named_entity.value=\"LOC\"/>";

        List<SearchResult> results = (ArrayList<SearchResult>) searchService.query(user, project,
                query);

        // Test results
        SearchResult expectedResult = new SearchResult();
        expectedResult.setDocumentId(sourceDocument.getId());
        expectedResult.setDocumentTitle("test");
        expectedResult.setText("Galicia ");
        expectedResult.setLeftContext("capital of ");
        expectedResult.setRightContext("is ");
        expectedResult.setOffsetStart(15);
        expectedResult.setOffsetEnd(22);
        expectedResult.setTokenStart(3);
        expectedResult.setTokenLength(1);

        assertNotNull(results);
        if (results != null) {
            assertEquals(1, results.size());
            assertEquals(expectedResult, results.get(0));
        }
    }

    @Configuration
    public static class TestContext
    {
        @Autowired
        ApplicationEventPublisher applicationEventPublisher;

        private final String temporaryFolderPath = "target/MtasDocumentIndexTest";
        private final File temporaryFolder;

        @Rule TemporaryFolder folder;
        
        public TestContext()
        {
            try {
                FileUtils.deleteDirectory(new File(temporaryFolderPath));
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            temporaryFolder = new File(temporaryFolderPath);
        }
        
        @Bean
        public ProjectService projectService()
        {
            return new ProjectServiceImpl();
        }

        @Bean
        public PhysicalIndexFactory mtasDocumentIndexFactory()
        {
            return new MtasDocumentIndexFactory();
        }

        @Bean
        public FeatureSupport featureSupport()
        {
            return new PrimitiveUimaFeatureSupport();
        }

        @Bean
        public FeatureSupportRegistry featureSupportRegistry(
                @Lazy @Autowired List<FeatureSupport> aFeatureSupports)
        {
            return new FeatureSupportRegistryImpl(aFeatureSupports);
        }

        @Bean
        public FeatureIndexingSupport primitiveUimaIndexingSupport(
                @Autowired FeatureSupportRegistry aFeatureSupportRegistry)
        {
            return new PrimitiveUimaIndexingSupport(aFeatureSupportRegistry);
        }

        @Bean
        public FeatureIndexingSupportRegistry featureIndexingSupportRegistry(
                @Lazy @Autowired(required = false) List<FeatureIndexingSupport> aIndexingSupports)
        {
            return new FeatureIndexingSupportRegistryImpl(aIndexingSupports);
        }

        @Lazy
        @Bean
        public NamedEntityLayerInitializer NamedEntityLayerInitializer(
                @Lazy @Autowired AnnotationSchemaService aAnnotationService)
        {
            return new NamedEntityLayerInitializer(aAnnotationService);
        }

        @Lazy
        @Bean
        public PartOfSpeechLayerInitializer PartOfSpeechLayerInitializer(
                @Lazy @Autowired AnnotationSchemaService aAnnotationSchemaService)
        {
            return new PartOfSpeechLayerInitializer(aAnnotationSchemaService);
        }

        @Lazy
        @Bean
        public TokenLayerInitializer TokenLayerInitializer(
                @Lazy @Autowired AnnotationSchemaService aAnnotationSchemaService)
        {
            return new TokenLayerInitializer(aAnnotationSchemaService);
        }

        @Lazy
        @Bean
        public PhysicalIndexRegistry indexRegistry(
                @Lazy @Autowired(required = false) List<PhysicalIndexFactory> aExtensions)
        {
            return new PhysicalIndexRegistryImpl(aExtensions);
        }

        @Bean
        public SearchService searchService()
        {
            return new SearchServiceImpl();
        }

        @Bean
        public UserDao userRepository()
        {
            return new UserDaoImpl();
        }

        @Bean
        public KnowledgeBaseService knowledgeBaseService()
        {
            return new KnowledgeBaseServiceImpl(temporaryFolder);
        }

        @Bean
        public IndexScheduler indexScheduler()
        {
            return new IndexScheduler();
        }

        @Bean
        public DocumentService documentService()
        {
            return new DocumentServiceImpl(repositoryProperties(), userRepository(),
                    casStorageService(), importExportService(), projectService(),
                    applicationEventPublisher);
        }

        @Bean
        public AnnotationSchemaService annotationSchemaService()
        {
            return new AnnotationSchemaServiceImpl();
        }

        @Bean
        public CasStorageService casStorageService()
        {
            return new CasStorageServiceImpl(null, repositoryProperties(), backupProperties());
        }

        @Bean
        public ImportExportService importExportService()
        {
            return new ImportExportServiceImpl();
        }

        @Bean
        public CurationDocumentService curationDocumentService()
        {
            return new CurationDocumentServiceImpl();
        }

        @Bean
        public RepositoryProperties repositoryProperties()
        {
            return new RepositoryProperties();
        }

        @Bean
        public BackupProperties backupProperties()
        {
            return new BackupProperties();
        }

        @Bean
        public Properties formats()
        {
            Properties props = new Properties();
            props.put("text.label", "Plain text");
            props.put("text.reader", TextReader.class.getName());
            props.put("text.writer", TextWriter.class.getName());
            props.put("conll2002.label", "CoNLL 2002");
            props.put("conll2002.reader",
                    de.tudarmstadt.ukp.dkpro.core.io.conll.Conll2002Reader.class.getName());
            props.put("conll2002.writer",
                    de.tudarmstadt.ukp.dkpro.core.io.conll.Conll2002Writer.class.getName());
            return props;
        }

        @Bean
        public ApplicationContextProvider contextProvider()
        {
            return new ApplicationContextProvider();
        }
    }
}