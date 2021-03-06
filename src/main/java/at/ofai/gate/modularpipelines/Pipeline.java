/*
 * Copyright (c) 2013 Austrian Research Institute for Artificial Intelligence (OFAI). 
 * Copyright (C) 2014-2016 The University of Sheffield.
 *
 * This file is part of gateplugin-ModularPipelines
 * (see https://github.com/johann-petrak/gateplugin-ModularPipelines)
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software. If not, see <http://www.gnu.org/licenses/>.
 */
package at.ofai.gate.modularpipelines;

import gate.Controller;
import gate.CorpusController;
import gate.Factory;
import gate.Factory.DuplicationContext;
import gate.FeatureMap;
import gate.LanguageAnalyser;
import gate.ProcessingResource;
import gate.Resource;
import gate.creole.AbstractLanguageAnalyser;
import gate.creole.ConditionalSerialAnalyserController;
import gate.creole.ControllerAwarePR;
import gate.creole.CustomDuplication;
import gate.creole.ExecutionException;
import gate.creole.ResourceInstantiationException;
import gate.creole.SerialAnalyserController;
import gate.creole.metadata.CreoleParameter;
import gate.creole.metadata.CreoleResource;
import gate.creole.metadata.HiddenCreoleParameter;
import gate.persist.PersistenceException;
import gate.util.GateRuntimeException;
import gate.util.persistence.PersistenceManager;

import java.io.IOException;
import java.net.URL;

import org.apache.log4j.Logger;

// TODO: add feature to delete document feature: deletedocfeature

/** 
 * A processing resource that wraps a controller loaded from a pipeline file.
 * This makes it possible to create modular pipelines which contain sub-pipelines
 * represented by this PR. The advantage over conventional nested pipelines
 * is that pipelines wrapped by this PR always represent the newest version
 * of the original pipeline file when they are loaded or re-initialized.
 * Re-initializing this PR will recursively delete all resources loaded by
 * the pipeline and reload a fresh copy of the pipeline from its pipeline file.
 * 
 * @author Johann Petrak
 */
@CreoleResource(name = "Pipeline",
        comment = "Represents a pipeline or corpus pipeline loaded from a xgapp/gapp file",
        helpURL="https://github.com/johann-petrak/gateplugin-modularpipelines/wiki/Pipline-PR")
public class Pipeline  extends AbstractLanguageAnalyser
  implements ProcessingResource, CustomDuplication, ControllerAwarePR {
  private static final long serialVersionUID = 1L;

  @CreoleParameter(comment="The URL of the saved pipeline file")
  public void setPipelineFileURL(URL fileURL) {
    pipelineFileURL = fileURL;
  }
  public URL getPipelineFileURL() {
    return pipelineFileURL;
  }
  protected URL pipelineFileURL = null;
  
  @CreoleParameter(comment="Used internally to indicate custom duplication")
  @HiddenCreoleParameter
  public void setIsCustomDuplicated(Boolean flag) {
    isCustomDuplicated = flag;
  }
  public Boolean getIsCustomDuplicated() {
    return isCustomDuplicated;
  }
  protected boolean isCustomDuplicated = false;
    
  protected Controller controller;
  
  
  protected static final Logger LOGGER = Logger
          .getLogger(Pipeline.class);
  
  
  @Override
  public Resource init() throws ResourceInstantiationException {
    if(getPipelineFileURL() == null) {
      throw new ResourceInstantiationException("pipelineFileURL must be set");
    }
    try {
      // TODO: not sure how the controller can ever be non-null in init()
      // therefore, we add some debugging code here ...
      if(controller == null) {
        if(!getIsCustomDuplicated()) {
          LOGGER.debug("Pipeline.init(): No controller, initializing pipeline from URL "+getPipelineFileURL());
          initialise_pipeline();
        } else {
          LOGGER.debug("Pipeline.init(): No controller, but not initialising pipeline, we got called from custom duplication for URL "+getPipelineFileURL());
        }
      } else {
        throw new ResourceInstantiationException("Pipeline.init(): controller is not null");
      }
    } catch (ResourceInstantiationException | PersistenceException | IOException ex) {
      throw new ResourceInstantiationException(
        "Could not load pipeline "+getPipelineFileURL(),ex);
    }
    super.init();
    return this;
  }
  
  @Override
  public void reInit() {
    Factory.deleteResource(controller);
    try {
      controller = null;
      initialise_pipeline();
    } catch (ResourceInstantiationException | PersistenceException | IOException ex) {
      throw new GateRuntimeException(
        "Could not re-load pipeline "+getPipelineFileURL(),ex);
    }
  }
  
  @Override
  public void interrupt() {
    controller.interrupt();
  }
  
  @Override
  public void execute() {
    // invoking a corpus controller will only work if the corpus is set,
    // even when the corpus is not used in a recursive invocation 
    // (if a corpus controller is invoked inside a corpus controller, the
    // document is set and the inner controller is only run on that single
    // document while the corpus is ignored).

    if(controller instanceof CorpusController) {      
      ((CorpusController)controller).setCorpus(corpus);      
    }
    if(controller instanceof LanguageAnalyser) {      
      ((LanguageAnalyser)controller).setDocument(document);      
    }
    try {
      LOGGER.debug(("Running pipeline "+controller.getName()+" on "+
              (document != null ? document.getName() : "(no document)" )));
      
      LOGGER.debug("PipelinePR "+this.getName()+" running execute of "+controller.getName());
      controller.execute();
      
    } catch (ExecutionException ex) {
      throw new GateRuntimeException(
        "Error executing pipeline "+pipelineFileURL,ex);
    } finally {
      if(controller instanceof LanguageAnalyser) {      
        ((LanguageAnalyser)controller).setDocument(null);      
      }
    }
  }
  
  boolean isEqual(Object one, Object two) {
    if(one == null && two == null) {
      return true;
    } else if(one == null) {
      return false;      
    } else if(two == null) {
      return false;
    } else {
      return one.equals(two);
    }
  }
  
  @Override
  public void cleanup() {
    LOGGER.debug("Pipeline.cleanup(): Deleting controller"+controller.getName());
    Factory.deleteResource(controller);
  }
  
  
  protected void initialise_pipeline() throws PersistenceException,
    IOException, ResourceInstantiationException {
    LOGGER.debug("(Re-)initialising pipeline "+pipelineFileURL);
    controller = (Controller)PersistenceManager.loadObjectFromUrl(pipelineFileURL);
  }
  
  @Override
  public Resource duplicate(DuplicationContext ctx)
      throws ResourceInstantiationException {
    LOGGER.debug("Pipeline.duplicate(): attempting to duplicate PiplinePR "+getPipelineFileURL());
    FeatureMap params = Factory.duplicate(getInitParameterValues(), ctx);
    // setting this hidden parameter will tell the init function not to 
    // load the controller even though the controller field will be null. 
    params.put("isCustomDuplicated", true); 
    params.putAll(Factory.duplicate(getRuntimeParameterValues(), ctx));
    FeatureMap features_here = Factory.duplicate(this.getFeatures(), ctx);
    // instead of letting the duplicate load the controller again, we 
    // create our own duplicated instance of the controller here ....
    LOGGER.debug("Pipeline.duplicate(): duplicating the controller for "+getPipelineFileURL());
    Controller c = (Controller)Factory.duplicate(this.controller, ctx);
    // ... create a duplicate of the PR but with no controller loaded
    LOGGER.debug("Pipeline.duplicate(): creating a copy of the PR for "+getPipelineFileURL());
    Pipeline resource = 
            (Pipeline)Factory.createResource(
              this.getClass().getName(), params, features_here, this.getName());
    // ... and set the controller in the duplicate to the duplicated controller
    // we just created
    LOGGER.debug("Pipeline.duplicate(): setting the controller of the duplicate for "+getPipelineFileURL());
    resource.controller = c;
    return resource;
  }
  @Override
  public void controllerExecutionStarted(Controller c)
      throws ExecutionException {
    if(controller instanceof ControllerAwarePR) {
      if(controller instanceof CorpusController) {
        ((CorpusController)controller).setCorpus(corpus);
      } 
      ((ControllerAwarePR)controller).controllerExecutionStarted(c);
    }    
  }
  @Override
  public void controllerExecutionFinished(Controller c)
      throws ExecutionException {
    if(controller instanceof ControllerAwarePR) {
      if(controller instanceof CorpusController) {
        ((CorpusController)controller).setCorpus(corpus);
      } 
      ((ControllerAwarePR)controller).controllerExecutionFinished(c);
      if(controller instanceof CorpusController) {
        ((CorpusController)controller).setCorpus(null);
      }
    }
  }
  @Override
  public void controllerExecutionAborted(Controller c, Throwable t)
      throws ExecutionException {
    if(controller instanceof ControllerAwarePR) {
      if(controller instanceof CorpusController) {
        ((CorpusController)controller).setCorpus(corpus);
      } 
      ((ControllerAwarePR)controller).controllerExecutionAborted(c, t);
      if(controller instanceof CorpusController) {
        ((CorpusController)controller).setCorpus(null);
      }
    }    
  }
  
  public void setConfig4Pipeline(URL configFileUrl) {
    if(controller instanceof ParametrizedCorpusController) {
      ParametrizedCorpusController pcc = (ParametrizedCorpusController)controller;
      LOGGER.debug("Re-setting the config file for sub pipeline "+pcc.getName());
      pcc.setConfigFileUrl(configFileUrl);
    }
  }
  
} // class Pipeline
