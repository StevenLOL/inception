/*
 * Copyright 2017
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
package de.tudarmstadt.ukp.inception.ui.dashboard;

import static de.tudarmstadt.ukp.clarin.webanno.api.SecurityUtil.annotationEnabeled;
import static de.tudarmstadt.ukp.clarin.webanno.api.SecurityUtil.curationEnabeled;
import static de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst.PAGE_PARAM_PROJECT_ID;
import static java.util.Arrays.asList;

import org.apache.wicket.Page;
import org.apache.wicket.Session;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.image.Image;
import org.apache.wicket.markup.html.link.StatelessLink;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.request.Url;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.request.resource.UrlResourceReference;
import org.apache.wicket.spring.injection.annot.SpringBean;

import de.tudarmstadt.ukp.clarin.webanno.api.ProjectService;
import de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaModel;
import de.tudarmstadt.ukp.clarin.webanno.ui.core.login.LoginPage;
import de.tudarmstadt.ukp.clarin.webanno.ui.core.menu.MenuItem;
import de.tudarmstadt.ukp.clarin.webanno.ui.core.menu.MenuItemRegistry;
import de.tudarmstadt.ukp.clarin.webanno.ui.core.page.ApplicationPageBase;
import de.tudarmstadt.ukp.inception.app.session.SessionMetaData;
import de.tudarmstadt.ukp.inception.ui.dashboard.dashlet.CurrentProjectDashlet;
import de.tudarmstadt.ukp.inception.ui.dashboard.dashlet.SystemStatusDashlet;

/**
 * Main menu page.
 */
public class DashboardPage extends ApplicationPageBase
{
    private static final long serialVersionUID = -2487663821276301436L;

    private @SpringBean ProjectService projectService;
    private @SpringBean UserDao userRepository;
    private @SpringBean MenuItemRegistry menuItemService;

    private ListView<MenuItem> menu;

    public DashboardPage()
    {
        setStatelessHint(true);
        setVersioned(false);
        
        // In case we restore a saved session, make sure the user actually still exists in the DB.
        // redirect to login page (if no usr is found, admin/admin will be created)
        User user = userRepository.getCurrentUser();
        if (user == null) {
            setResponsePage(LoginPage.class);
        }
        
        // if not either a curator or annotator, display warning message
        if (
                !annotationEnabeled(projectService, user, WebAnnoConst.PROJECT_TYPE_ANNOTATION) && 
                !annotationEnabeled(projectService, user, WebAnnoConst.PROJECT_TYPE_AUTOMATION) && 
                !annotationEnabeled(projectService, user, WebAnnoConst.PROJECT_TYPE_CORRECTION) && 
                !curationEnabeled(projectService, user)) 
        {
            info("You are not member of any projects to annotate or curate");
        }
        
        menu = new ListView<MenuItem>("menu", LambdaModel.of(menuItemService::getMenuItems))
        {
            private static final long serialVersionUID = -5492972164756003552L;

            @Override
            protected void populateItem(ListItem<MenuItem> aItem)
            {
                MenuItem item = aItem.getModelObject();
                final Class<? extends Page> pageClass = item.getPageClass(); 
                StatelessLink<Void> menulink = new StatelessLink<Void>("menulink") {
                    private static final long serialVersionUID = 4110674757822252390L;

                    @Override
                    public void onClick()
                    {
                        Project project = Session.get().getMetaData(
                                SessionMetaData.CURRENT_PROJECT);
                        // For legacy WebAnno pages, we set PAGE_PARAM_PROJECT_ID while INCEpTION
                        // pages may pick the project up from the session.
                        PageParameters params = new PageParameters();
                        if (project != null) {
                            params.set(PAGE_PARAM_PROJECT_ID, project.getId());
                        }
                        setResponsePage(pageClass, params);
                    }
                };
                menulink.add(
                        new Image("icon", new UrlResourceReference(Url.parse(item.getIcon()))));
                menulink.add(new Label("label", item.getLabel()));
                
                Project project = Session.get().getMetaData(SessionMetaData.CURRENT_PROJECT);

                boolean isAdminItem = asList("ProjectPage", "ManageUsersPage")
                        .contains(item.getPageClass().getSimpleName());
                
                aItem.add(menulink);
                aItem.setVisible(item.applies() && (project != null || isAdminItem));
            }
        };
        add(menu);
        
        add(new CurrentProjectDashlet("currentProjectDashlet"));
        add(new SystemStatusDashlet("systemStatusDashlet"));
    }
}
