/*!
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright (c) 2002-2013 Pentaho Corporation..  All rights reserved.
 */

package org.pentaho.platform.web.http.api.resources.services;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.action.IAction;
import org.pentaho.platform.api.engine.IAuthorizationPolicy;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.UnifiedRepositoryException;

import org.pentaho.platform.api.scheduler2.IBlockoutManager;
import org.pentaho.platform.api.scheduler2.IJobTrigger;
import org.pentaho.platform.api.scheduler2.IScheduler;
import org.pentaho.platform.api.scheduler2.Job;
import org.pentaho.platform.api.scheduler2.SchedulerException;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.repository.RepositoryFilenameUtils;
import org.pentaho.platform.security.policy.rolebased.actions.SchedulerAction;
import org.pentaho.platform.util.messages.LocaleHelper;
import org.pentaho.platform.web.http.api.resources.JobRequest;

import org.pentaho.platform.web.http.api.resources.proxies.BlockStatusProxy;
import org.pentaho.platform.web.http.api.resources.JobScheduleParam;
import org.pentaho.platform.web.http.api.resources.JobScheduleRequest;
import org.pentaho.platform.web.http.api.resources.RepositoryFileStreamProvider;
import org.pentaho.platform.web.http.api.resources.SchedulerOutputPathResolver;
import org.pentaho.platform.web.http.api.resources.SchedulerResourceUtil;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SchedulerService {

  protected IScheduler scheduler;

  protected IAuthorizationPolicy policy;

  protected IUnifiedRepository repository;

  protected static IBlockoutManager blockoutManager =
    PentahoSystem.get( IBlockoutManager.class, "IBlockoutManager", null ); //$NON-NLS-1$;

  private static final Log logger = LogFactory.getLog( FileService.class );

  public Job createJob( JobScheduleRequest scheduleRequest )
    throws IOException, SchedulerException, IllegalAccessException {

    // Used to determine if created by a RunInBackgroundCommand
    boolean runInBackground =
      scheduleRequest.getSimpleJobTrigger() == null && scheduleRequest.getComplexJobTrigger() == null
        && scheduleRequest.getCronJobTrigger() == null;

    if ( !runInBackground && !policy.isAllowed( SchedulerAction.NAME ) ) {
      throw new SecurityException();
    }

    boolean hasInputFile = !StringUtils.isEmpty( scheduleRequest.getInputFile() );
    RepositoryFile file = null;
    if ( hasInputFile ) {
      try {
        file = getRepository().getFile( scheduleRequest.getInputFile() );
      } catch ( UnifiedRepositoryException ure ) {
        hasInputFile = false;
        logger.warn( ure.getMessage(), ure );
      }
    }

    // if we have an inputfile, generate job name based on that if the name is not passed in
    if ( hasInputFile && StringUtils.isEmpty( scheduleRequest.getJobName() ) ) {
      scheduleRequest.setJobName( file.getName().substring( 0, file.getName().lastIndexOf( "." ) ) ); //$NON-NLS-1$
    } else if ( !StringUtils.isEmpty( scheduleRequest.getActionClass() ) ) {
      String actionClass =
        scheduleRequest.getActionClass().substring( scheduleRequest.getActionClass().lastIndexOf( "." ) + 1 );
      scheduleRequest.setJobName( actionClass ); //$NON-NLS-1$
    } else if ( !hasInputFile && StringUtils.isEmpty( scheduleRequest.getJobName() ) ) {
      // just make up a name
      scheduleRequest.setJobName( "" + System.currentTimeMillis() ); //$NON-NLS-1$
    }

    if ( hasInputFile ) {
      Map<String, Serializable> metadata = getRepository().getFileMetadata( file.getId() );
      if ( metadata.containsKey( "_PERM_SCHEDULABLE" ) ) {
        boolean schedulable = Boolean.parseBoolean( (String) metadata.get( "_PERM_SCHEDULABLE" ) );
        if ( !schedulable ) {
          throw new IllegalAccessException();
        }
      }
    }

    Job job = null;
    IJobTrigger jobTrigger = SchedulerResourceUtil.convertScheduleRequestToJobTrigger( scheduleRequest, scheduler );

    HashMap<String, Serializable> parameterMap = new HashMap<String, Serializable>();
    for ( JobScheduleParam param : scheduleRequest.getJobParameters() ) {
      parameterMap.put( param.getName(), param.getValue() );
    }

    if ( isPdiFile( file ) ) {
      parameterMap = handlePDIScheduling( file, parameterMap );
    }

    parameterMap.put( LocaleHelper.USER_LOCALE_PARAM, LocaleHelper.getLocale() );

    if ( hasInputFile ) {
      SchedulerOutputPathResolver outputPathResolver = getSchedulerOutputPathResolver( scheduleRequest );
      String outputFile = outputPathResolver.resolveOutputFilePath();
      String actionId =
        getExtension( scheduleRequest.getInputFile() )
          + ".backgroundExecution"; //$NON-NLS-1$ //$NON-NLS-2$
      job =
        getScheduler().createJob( scheduleRequest.getJobName(), actionId, parameterMap, jobTrigger,
          new RepositoryFileStreamProvider( scheduleRequest.getInputFile(), outputFile,
            getAutoCreateUniqueFilename( scheduleRequest ) ) );
    } else {
      // need to locate actions from plugins if done this way too (but for now, we're just on main)
      String actionClass = scheduleRequest.getActionClass();
      try {
        @SuppressWarnings( "unchecked" )
        Class<IAction> iaction = getAction( actionClass );
        job = getScheduler().createJob( scheduleRequest.getJobName(), iaction, parameterMap, jobTrigger );
      } catch ( ClassNotFoundException e ) {
        throw new RuntimeException( e );
      }
    }

    return job;
  }

  public Job triggerNow( JobRequest jobRequest ) throws SchedulerException {
    Job job = getScheduler().getJob( jobRequest.getJobId() );
    if ( getPolicy().isAllowed( SchedulerAction.NAME ) ) {
      getScheduler().triggerNow( jobRequest.getJobId() );
    } else {
      if ( getSession().getName().equals( job.getUserName() ) ) {
        getScheduler().triggerNow( jobRequest.getJobId() );
      }
    }
    // udpate job state
    job = getScheduler().getJob( jobRequest.getJobId() );

    return job;
  }

  public List<Job> getBlockOutJobs() {
    return blockoutManager.getBlockOutJobs();
  }

  public boolean hasBlockouts() {
    List<Job> jobs = blockoutManager.getBlockOutJobs();
    return jobs != null && jobs.size() > 0;
  }

  public boolean willFire( IJobTrigger trigger ) {
    return blockoutManager.willFire( trigger );
  }
  
  public boolean shouldFireNow() {
    return blockoutManager.shouldFireNow();    
  }
  
  public BlockStatusProxy getBlockStatus( IJobTrigger trigger ) {
    Boolean totallyBlocked = false;
    Boolean partiallyBlocked = blockoutManager.isPartiallyBlocked( trigger );
    if ( partiallyBlocked ) {
      totallyBlocked = !blockoutManager.willFire( trigger );
    }
    return new BlockStatusProxy( totallyBlocked, partiallyBlocked );
  }
  
  protected IPentahoSession getSession() {
    return PentahoSessionHolder.getSession();
  }

  public Class<IAction> getAction( String actionClass ) throws ClassNotFoundException {
    return ( (Class<IAction>) Class.forName( actionClass ) );
  }

  public IUnifiedRepository getRepository() {
    if ( repository == null ) {
      repository = PentahoSystem.get( IUnifiedRepository.class );
    }
    return repository;
  }

  public IScheduler getScheduler() {
    if ( scheduler == null ) {
      scheduler = PentahoSystem.get( IScheduler.class, "IScheduler2", null );
    }

    return scheduler;
  }

  public IAuthorizationPolicy getPolicy() {
    if ( policy == null ) {
      policy = PentahoSystem.get( IAuthorizationPolicy.class );
    }

    return policy;
  }

  protected SchedulerOutputPathResolver getSchedulerOutputPathResolver( JobScheduleRequest scheduleRequest ) {
    return new SchedulerOutputPathResolver( scheduleRequest );
  }

  protected boolean isPdiFile( RepositoryFile file ) {
    return SchedulerResourceUtil.isPdiFile( file );
  }

  protected HashMap<String, Serializable> handlePDIScheduling( RepositoryFile file,
                                                                   HashMap<String, Serializable> parameterMap ) {
    return SchedulerResourceUtil.handlePDIScheduling( file, parameterMap );
  }

  public boolean getAutoCreateUniqueFilename( final JobScheduleRequest scheduleRequest ) {
    ArrayList<JobScheduleParam> jobParameters = scheduleRequest.getJobParameters();
    for ( JobScheduleParam jobParameter : jobParameters ) {
      if ( "autoCreateUniqueFilename".equals( jobParameter.getName() ) && "boolean".equals( jobParameter.getType() ) ) {
        return (Boolean) jobParameter.getValue();
      }
    }
    return true;
  }

  protected String getExtension( String filename ) {
    return RepositoryFilenameUtils.getExtension( filename );
  }
}