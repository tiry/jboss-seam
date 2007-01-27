package org.jboss.seam.security;

import static org.jboss.seam.ScopeType.SESSION;
import static org.jboss.seam.annotations.Install.BUILT_IN;

import java.io.IOException;
import java.io.Serializable;
import java.security.Principal;
import java.security.acl.Group;
import java.security.acl.Permission;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.faces.context.FacesContext;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;

import org.drools.FactHandle;
import org.drools.RuleBase;
import org.drools.WorkingMemory;
import org.jboss.seam.Component;
import org.jboss.seam.ScopeType;
import org.jboss.seam.Seam;
import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Install;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.core.Expressions.MethodBinding;
import org.jboss.seam.security.config.SecurityConfiguration;
import org.jboss.seam.security.config.SecurityConfiguration.Role;
import org.jboss.seam.security.rules.PermissionCheck;
import org.jboss.seam.security.spi.SeamLoginModule;
import org.jboss.seam.util.UnifiedELValueBinding;

@Name("org.jboss.seam.security.identity")
@Scope(SESSION)
@Install(precedence = BUILT_IN, classDependencies="org.drools.WorkingMemory")
public class Identity implements Serializable
{  
   private static final long serialVersionUID = 3751659008033189259L;
   
   private static final String DEFAULT_CONFIG_NAME = "default";   
   
   private class LoginModuleConfiguration extends Configuration
   {
      private Map<String,AppConfigurationEntry[]> entries = new HashMap<String,AppConfigurationEntry[]>();
      
      public void addEntry(String name, AppConfigurationEntry[] entry)
      {
         entries.put(name, entry);
      }
      
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name)
      {
         return entries.get(name);
      }
      
      @Override
      public void refresh() { }
   }
   
   private static LoginModuleConfiguration defaultConfig = null;
      
   private String username;
   private String password;
   
   private MethodBinding authMethod;

   protected Principal principal;   
   protected Subject subject;
   
   @In(create = true, required = false)
   private RuleBase securityRules;
   
   private WorkingMemory securityContext;
      
   @Create
   public void create()
   {
      subject = new Subject();      
      securityContext = securityRules.newWorkingMemory(false);
   }

   public static Identity instance()
   {
      if (!Contexts.isSessionContextActive())
         throw new IllegalStateException("No active session context");

      Identity instance = (Identity) Component.getInstance(Identity.class,
            ScopeType.SESSION, true);

      if (instance == null)
      {
         throw new IllegalStateException(
               "No Identity could be created");
      }

      return instance;
   }
   
   /**
    * If there is a principal set, then the user is logged in.
    * 
    */
   public boolean isLoggedIn()
   {
      return getPrincipal() != null;
   }

   public Principal getPrincipal()
   {
      if (principal == null)
      {
         Set<Principal> principals = subject.getPrincipals(Principal.class);
         if (!principals.isEmpty())
            principal = principals.iterator().next();
      }
      
      return principal;
   }
   
   public Subject getSubject()
   {
      return subject;
   }

   /**
    * Checks if the authenticated user contains the specified role.
    * 
    * @param role String
    * @return boolean Returns true if the authenticated user contains the role,
    *         or false if otherwise.
    */
   public boolean isUserInRole(String role)
   {
      for (Group sg : subject.getPrincipals(Group.class))      
      {
         if ("roles".equals(sg.getName()))
         {
            return sg.isMember(new SimplePrincipal(role));
         }
      }
      
      return false;
   }
      
   /**
    * Performs an authorization check, based on the specified security expression.
    * 
    * @param expr The security expression to evaluate
    * @throws NotLoggedInException Thrown if the authorization check fails and 
    * the user is not authenticated
    * @throws AuthorizationException Thrown if the authorization check fails and
    * the user is authenticated
    */
   public void checkRestriction(String expr)
   {      
      if (!evaluateExpression(expr))
      {
         if (!isLoggedIn())
         {
            throw new NotLoggedInException();
         }
         else
         {
            throw new AuthorizationException(String.format(
               "Authorization check failed for expression [%s]", expr));
         }
      }
   }
   
   public void login()
      throws LoginException
   {
      login(null);      
   }
   
   public void login(LoginContext loginContext)
      throws LoginException
   {
      CallbackHandler handler = createCallbackHandler(username, password);
   
      if (loginContext == null)
      {
         loginContext = new LoginContext(DEFAULT_CONFIG_NAME, subject, handler, 
                  getConfiguration());
      }
      
      loginContext.login();
      
      password = null;
      postLogin();
   }
   
   public void logout()
   {
      username = null;
      password = null;
      principal = null;
      
      subject = new Subject();
      securityContext = securityRules.newWorkingMemory(false);
   }

   /**
    * Checks if the authenticated Identity is a member of the specified role.
    * 
    * @param name String The name of the role to check
    * @return boolean True if the user is a member of the specified role
    */
   public boolean hasRole(String name)
   {
      if (!Contexts.isSessionContextActive() || !Contexts.getSessionContext().isSet(
            Seam.getComponentName(Identity.class)))
      {
         return false;
      }
     
      return isUserInRole(name);
   }

   /**
    * Performs a permission check for the specified name and action
    * 
    * @param name String The permission name
    * @param action String The permission action
    * @param arg Object Optional object parameter used to make a permission decision
    * @return boolean True if the user has the specified permission
    */
   public boolean hasPermission(String name, String action, Object arg)
   {
      List<FactHandle> handles = new ArrayList<FactHandle>();

      PermissionCheck check = new PermissionCheck(name, action);

      handles.add(securityContext.assertObject(check));

      if (arg != null)
      {
         handles.add(securityContext.assertObject(arg));
      }      
      
      // this doesn't work?
//      for (String nm : Contexts.getMethodContext().getNames())
//      {
//         handles.add(securityContext.assertObject(Contexts.getMethodContext().get(nm)));
//      }

      securityContext.fireAllRules();

      for (FactHandle handle : handles)
         securityContext.retractObject(handle);
      
      return check.isGranted();
   }
   
   
   /**
    * Creates a callback handler that can handle a standard username/password
    * callback, using the specified username and password parameters.
    * 
    * @param username The username to provide for a NameCallback
    * @param password The password to provide for a PasswordCallback
    */
   public CallbackHandler createCallbackHandler(final String username, 
         final String password)
   {
      return new CallbackHandler() {
         public void handle(Callback[] callbacks) 
            throws IOException, UnsupportedCallbackException 
         {
            for (int i = 0; i < callbacks.length; i++)
            {
               if (callbacks[i] instanceof NameCallback)
                  ((NameCallback) callbacks[i]).setName(username);
               else if (callbacks[i] instanceof PasswordCallback)
                  ((PasswordCallback) callbacks[i]).setPassword(password.toCharArray());
               else
                  throw new UnsupportedCallbackException(callbacks[i],
                        "Unsupported callback");
            }
            
         }
      };
   }

   protected Configuration getConfiguration()
   {
      if (defaultConfig == null)
      {
         createDefaultConfig();
      }
      
      return defaultConfig;
   }
   
   private synchronized void createDefaultConfig()
   {
      if (defaultConfig == null)
      {
         defaultConfig = new LoginModuleConfiguration();
         Map<String,String> options = new HashMap<String,String>();
         AppConfigurationEntry[] entries = new AppConfigurationEntry[] {
            new AppConfigurationEntry(SeamLoginModule.class.getName(), 
                     LoginModuleControlFlag.REQUIRED, options)
         };
         defaultConfig.addEntry(DEFAULT_CONFIG_NAME, entries);
      }
   }
   
   /**
    * Populates the specified subject's roles with any inherited roles
    * according to the role memberships contained within the current 
    * SecurityConfiguration
    * 
    * @param subject The subject containing the role group.
    */
   private void postLogin()
   {
      // Populate the working memory with the user's principals
      for (Principal p : subject.getPrincipals())
      {         
         if (p instanceof Group && "roles".equals(((Group) p).getName()))
         {
            SecurityConfiguration config = SecurityConfiguration.instance();
            
            Enumeration e = ((Group) p).members();
            while (e.hasMoreElements())
            {
               Principal role = (Principal) e.nextElement();
               
               Role r = config.getSecurityRole(role.getName());
               if (r.getPermissions() != null)
               {
                  for (Permission perm : r.getPermissions())
                  {
                     securityContext.assertObject(perm);
                  }
               }
            }
         }
         else
         {
            securityContext.assertObject(p);            
         }
      }
      
      for (SimpleGroup grp : subject.getPrincipals(SimpleGroup.class))
      {
         if ("roles".equals(grp.getName()))
         {
            Set<Principal> memberships = new HashSet<Principal>();
            SecurityConfiguration config = SecurityConfiguration.instance();
            
            Enumeration e = grp.members();
            while (e.hasMoreElements())
            {
               Principal role = (Principal) e.nextElement();
               addRoleMemberships(memberships, role.getName(), config);               
            }
            
            for (Principal r : memberships)
               grp.addMember(r);
            
            break;
         }
      }
   }
   
   /**
    * Recursively adds role memberships to the specified role set, for the
    * specified role name.  The security configuration is passed in each time
    * so that a context lookup doesn't need to take place each time.
    * 
    * @param roles The set that role memberships are to be added to
    * @param roleName The name of the role to add memberships for
    * @param config The security configuration
    */
   private void addRoleMemberships(Set<Principal> roles, String roleName, 
         SecurityConfiguration config)
   {
      // Retrieve the role configuration
      Role role = config.getSecurityRole(roleName);
      
      // For each of the role's configured memberships, check if the roles
      // parameter already contains the membership.  If it doesn't add it,
      // and make a recursive call to add the membership role's memberships.
      for (String membership : role.getMemberships())
      {
         SimplePrincipal r = new SimplePrincipal(membership);
         if (!roles.contains(r))
         {
            roles.add(r);
            addRoleMemberships(roles, membership, config);
         }
      }      
   }      
   
   /**
    * Evaluates the specified security expression, which must return a boolean
    * value.
    * 
    * @param expr String The expression to evaluate
    * @return boolean The result of the expression evaluation
    */
   public boolean evaluateExpression(String expr) 
       throws AuthorizationException
   {     
      return (Boolean) new UnifiedELValueBinding(expr).getValue(FacesContext.getCurrentInstance());
   }   
   
   public String getUsername()
   {
      return username;
   }
   
   public void setUsername(String username)
   {
      this.username = username;
   }
   
   public String getPassword()
   {
      return null;
   }
   
   public void setPassword(String password)
   {
      this.password = password;
   }
   
   public WorkingMemory getSecurityContext()
   {
      return securityContext;
   }
   
   public MethodBinding getAuthMethod()
   {
      return authMethod;
   }
   
   public void setAuthMethod(MethodBinding authMethod)
   {
      this.authMethod = authMethod;
   }
}
