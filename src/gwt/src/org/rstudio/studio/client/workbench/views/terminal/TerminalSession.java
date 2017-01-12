/*
 * TerminalSession.java
 *
 * Copyright (C) 2009-16 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.workbench.views.terminal;

import org.rstudio.core.client.CommandWithArg;
import org.rstudio.core.client.HandlerRegistrations;
import org.rstudio.core.client.StringUtil;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.application.events.SessionSerializationEvent;
import org.rstudio.studio.client.application.events.SessionSerializationHandler;
import org.rstudio.studio.client.application.model.SessionSerializationAction;
import org.rstudio.studio.client.common.SimpleRequestCallback;
import org.rstudio.studio.client.common.console.ConsoleOutputEvent;
import org.rstudio.studio.client.common.console.ConsoleProcess;
import org.rstudio.studio.client.common.console.ConsoleProcessInfo;
import org.rstudio.studio.client.common.console.ProcessExitEvent;
import org.rstudio.studio.client.common.shell.ShellInput;
import org.rstudio.studio.client.common.shell.ShellSecureInput;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.server.VoidServerRequestCallback;
import org.rstudio.studio.client.workbench.model.WorkbenchServerOperations;
import org.rstudio.studio.client.workbench.views.terminal.events.ResizeTerminalEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalDataInputEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalSessionStartedEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalSessionStoppedEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalSubprocEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalTitleEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.XTermTitleEvent;
import org.rstudio.studio.client.workbench.views.terminal.xterm.XTermWidget;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.inject.Inject;

/**
 * A connected Terminal session.
 */
public class TerminalSession extends XTermWidget
                             implements ConsoleOutputEvent.Handler, 
                                        ProcessExitEvent.Handler,
                                        ResizeTerminalEvent.Handler,
                                        TerminalDataInputEvent.Handler,
                                        XTermTitleEvent.Handler,
                                        SessionSerializationHandler,
                                        TerminalSubprocEvent.Handler
{
   /**
    * 
    * @param secureInput securely send user input to server
    * @param sequence number used as part of default terminal title
    * @param handle terminal handle if reattaching, null if new terminal
    * @param caption terminal caption if reattaching, null if new terminal
    * @param title widget title
    */
   public TerminalSession(final ShellSecureInput secureInput, 
                          int sequence,
                          String handle,
                          String caption,
                          String title,
                          boolean hasChildProcs)
   {
      RStudioGinjector.INSTANCE.injectMembers(this);
      secureInput_ = secureInput;
      sequence_ = sequence;
      terminalHandle_ = handle;
      hasChildProcs_ = hasChildProcs;
      setTitle(title);

      if (StringUtil.isNullOrEmpty(caption))
         caption_ = "Terminal " + sequence_;
      else
         caption_ = caption;

      setHeight("100%");
   }

   @Inject
   private void initialize(WorkbenchServerOperations server,
                           EventBus events)
   {
      server_ = server;
      eventBus_ = events; 
   } 

   /**
    * Create a terminal process and connect to it.
    */
   public void connect()
   {
      if (connected_ || connecting_)
         return;

      connecting_ = true;
      setNewTerminal(getHandle() == null);

      server_.startTerminal(80, 25, getHandle(), getCaption(), getTitle(), getSequence(),
                            new ServerRequestCallback<ConsoleProcess>()
      {
         @Override
         public void onResponseReceived(ConsoleProcess consoleProcess)
         {
            consoleProcess_ = consoleProcess;
            if (consoleProcess_ == null)
            {
               writeError("No ConsoleProcess received from server");
               disconnect();
               return;
            }

            if (getInteractionMode() != ConsoleProcessInfo.INTERACTION_ALWAYS)
            {
               writeError("Unsupported ConsoleProcess interaction mode");
               disconnect();
               return;
            } 

            addHandlerRegistration(consoleProcess_.addConsoleOutputHandler(TerminalSession.this));
            addHandlerRegistration(consoleProcess_.addProcessExitHandler(TerminalSession.this));
            addHandlerRegistration(addResizeTerminalHandler(TerminalSession.this));
            addHandlerRegistration(addXTermTitleHandler(TerminalSession.this));
            addHandlerRegistration(eventBus_.addHandler(SessionSerializationEvent.TYPE, TerminalSession.this));
            addHandlerRegistration(eventBus_.addHandler(TerminalSubprocEvent.TYPE, TerminalSession.this));

            // We keep this handler connected after a terminal disconnect so
            // user input can wake up a suspended session
            if (terminalInputHandler_ == null)
               terminalInputHandler_ = addTerminalDataInputHandler(TerminalSession.this);

            consoleProcess.start(new ServerRequestCallback<Void>()
            {
               @Override
               public void onResponseReceived(Void response)
               {
                  connected_ = true;
                  connecting_ = false;
                  sendUserInput();
                  eventBus_.fireEvent(new TerminalSessionStartedEvent(TerminalSession.this));
               }

               @Override
               public void onError(ServerError error)
               {
                  disconnect();
                  writeError(error.getUserMessage());
               }
            });
         }

         @Override
         public void onError(ServerError error)
         {
            disconnect();
            writeError(error.getUserMessage());
         }

      });
   }

   /**
    * Disconnect a connected terminal. Allows for reconnection.
    */
   private void disconnect()
   {
      inputQueue_.setLength(0);
      registrations_.removeHandler();
      consoleProcess_ = null;
      connected_ = false;
      connecting_ = false;
   }

   @Override
   public void onConsoleOutput(ConsoleOutputEvent event)
   {
      write(event.getOutput());
   }

   @Override
   public void onProcessExit(ProcessExitEvent event)
   {
      unregisterHandlers();
      if (consoleProcess_ != null)
      {
         consoleProcess_.reap(new VoidServerRequestCallback());
      }

      eventBus_.fireEvent(new TerminalSessionStoppedEvent(this));
   }

   @Override
   public void onResizeTerminal(ResizeTerminalEvent event)
   {
      consoleProcess_.resizeTerminal(
            event.getCols(), event.getRows(),
            new VoidServerRequestCallback() 
            {
               @Override
               public void onError(ServerError error)
               {
                  writeln(error.getUserMessage());
               }
            });
   }

   @Override
   public void onTerminalDataInput(TerminalDataInputEvent event)
   {
      if (event.getData() != null)
      {
         inputQueue_.append(event.getData());
      }

      if (!connected_)
      {
         // accumulate user input until we are connected, then play it back
         connect();
         return;
      }

      sendUserInput();
   }

   /**
    * Send user input to the server, breaking down into chunks small
    * enough for the encryption.
    * @param userInput string to send
    */
   private void sendUserInput()
   {
      final int MAXENCRYPT = 117;
      String userInput;

      if (inputQueue_.length() == 0)
      {
         return;
      }
      if (inputQueue_.length() > MAXENCRYPT)
      {
         userInput = inputQueue_.substring(0, MAXENCRYPT);
         inputQueue_.delete(0,  MAXENCRYPT);
      }
      else
      {
         userInput = inputQueue_.toString();
         inputQueue_.setLength(0);
      }

      secureInput_.secureString(userInput, new CommandWithArg<String>() 
      {
         @Override
         public void execute(String arg) // success
         {
            consoleProcess_.writeStandardInput(
                  ShellInput.create(arg,  true /* echo input*/), 
                  new VoidServerRequestCallback() {

                     @Override
                     public void onResponseReceived(Void response)
                     {
                        sendUserInput();
                     }

                     @Override
                     public void onError(ServerError error)
                     {
                        writeln(error.getUserMessage());
                     }
                  });
         }
      },
      new CommandWithArg<String>()
      {
         @Override
         public void execute(String errorMessage) // failure
         {
            writeln(errorMessage); 
         }
      });
   }

   @Override
   public void onXTermTitle(XTermTitleEvent event)
   {
      setTitle(event.getTitle());
      eventBus_.fireEvent(new TerminalTitleEvent(this));
   }

   @Override
   public void setTitle(String title)
   {
      // don't call superclass, don't want this acting as default tool-tip
      // for the widget
      title_ = title;
   }

   @Override
   public String getTitle()
   {
      return title_;
   }

   /**
    * @return terminal caption, such as "Terminal 1"
    */
   public String getCaption()
   {
      return caption_;
   }

   /**
    * Set caption, user customizable identifier for this terminal session.
    * @param caption new caption
    */
   public void setCaption(String caption)
   {
      if (StringUtil.isNullOrEmpty(caption))
         return;

      caption_ = caption;
   }
   
   /**
    * Erase the scrollback buffer on the client and server.
    */
   public void clearBuffer()
   {
      clear();
      if (consoleProcess_ != null)
      {
         consoleProcess_.eraseTerminalBuffer(new SimpleRequestCallback<Void>());
      }
   }

   private int getInteractionMode()
   {
      if (consoleProcess_ != null)
         return consoleProcess_.getProcessInfo().getInteractionMode();
      else
         return ConsoleProcessInfo.INTERACTION_NEVER;
   } 

   protected void addHandlerRegistration(HandlerRegistration reg)
   {
      registrations_.add(reg);
   }

   protected void unregisterHandlers()
   {
      registrations_.removeHandler();
      if (terminalInputHandler_ != null)
      {
         terminalInputHandler_.removeHandler();
         terminalInputHandler_ = null;
      }
   }

   protected void writeError(String msg)
   {
      write(AnsiCode.ForeColor.RED + "Error: " + msg + AnsiCode.DEFAULTCOLORS);
   }

   @Override
   protected void onLoad()
   {
      super.onLoad();
      connect();
   }

   @Override
   protected void onDetach()
   {
      super.onDetach();
      disconnect();
      unregisterHandlers();
   }

   @Override
   public void setVisible(boolean isVisible)
   {
      super.setVisible(isVisible);
      if (isVisible)
      {
         connect();

         // Inform the terminal that there may have been a resize. This could 
         // happen on first display, or if the terminal was hidden behind other
         // terminal sessions and there was a resize.
         // A delay is needed to give the xterm.js implementation an
         // opportunity to be ready for this.
         Scheduler.get().scheduleDeferred(new ScheduledCommand()
         {
            @Override
            public void execute()
            {
               onResize();
            }
         });
      }
   }

   /**
    * A unique handle for this terminal instance. Corresponds to the 
    * server-side ConsoleProcess handle.
    * @return Opaque string handle for this terminal instance, or null if
    * terminal has never been attached to a server ConsoleProcess.
    */
   public String getHandle()
   {
      if (consoleProcess_ == null)
      {
         return terminalHandle_;
      }
      terminalHandle_ = consoleProcess_.getProcessInfo().getHandle();
      return terminalHandle_;
   }

   /**
    * Does this terminal's shell program (i.e. bash) have any child processes?
    * @return true if it has child processes, or it hasn't been determined yet
    */
   public boolean getHasChildProcs()
   {
      return hasChildProcs_;
   }

   /**
    * The sequence number of the terminal, used in creation of the default
    * title, e.g. "Terminal 3".
    * @return The sequence number that was passed to the constructor.
    */
   public int getSequence()
   {
      return sequence_;
   }

   /**
    * Forcibly terminate the process associated with this terminal session.
    */
   public void terminate()
   {
      if (consoleProcess_ != null)
      {
         consoleProcess_.interrupt(new SimpleRequestCallback<Void>()
         {
            @Override
            public void onResponseReceived(Void response)
            {
               consoleProcess_.reap(new VoidServerRequestCallback());
            }
         });
      }
   }

   @Override
   public void onSessionSerialization(SessionSerializationEvent event)
   {
      switch(event.getAction().getType())
      {
      case SessionSerializationAction.SUSPEND_SESSION:
         disconnect();
         break;
      }
   }

   @Override
   public void onTerminalSubprocs(TerminalSubprocEvent event)
   {
      hasChildProcs_ = event.hasSubprocs();
   }

   /**
    * @return true if terminal is connected to server, false if not
    */
   public boolean isConnected()
   {
      return connected_;
   }

   /**
    * Perform actions when the terminal is ready.
    */
   @Override
   protected void terminalReady()
   {
      if (newTerminal_)
      {
         writeln("Welcome to " + AnsiCode.ForeColor.LIGHTBLUE + "RStudio" +
                 AnsiCode.DEFAULTCOLORS + " terminal.");
         setNewTerminal(false);
      }
      else
      {
         Scheduler.get().scheduleDeferred(new ScheduledCommand()
         {
            @Override
            public void execute()
            {
               onResize();
               if (consoleProcess_ != null)
               {
                  consoleProcess_.getTerminalBuffer(new ServerRequestCallback<String>()
                  {
                     @Override
                     public void onResponseReceived(String buffer)
                     {
                        write(buffer);
                     }

                     @Override
                     public void onError(ServerError error)
                     {
                        writeError(error.getUserMessage());
                     }
                   });
               }
            }
         });
       }
   }

   /**
    * Set if connecting to a new terminal session.
    * @param isNew true if a new connection, false if a reconnect
    */
   private void setNewTerminal(boolean isNew)
   {
      newTerminal_ = isNew;
   }

   private final ShellSecureInput secureInput_;
   private HandlerRegistrations registrations_ = new HandlerRegistrations();
   private HandlerRegistration terminalInputHandler_;
   private ConsoleProcess consoleProcess_;
   private String caption_;
   private String title_;
   private final int sequence_;
   private String terminalHandle_;
   private boolean hasChildProcs_;
   private boolean connected_;
   private boolean connecting_;
   private StringBuilder inputQueue_ = new StringBuilder();
   private boolean newTerminal_ = true;

   // Injected ---- 
   private WorkbenchServerOperations server_; 
   private EventBus eventBus_;
}