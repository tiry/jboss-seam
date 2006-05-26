package org.jboss.seam.jms;

import static org.jboss.seam.InterceptionType.NEVER;

import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueSender;
import javax.naming.NamingException;

import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.Destroy;
import org.jboss.seam.annotations.Intercept;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.Unwrap;
import org.jboss.seam.util.Naming;

@Scope(ScopeType.EVENT)
@Intercept(NEVER)
public class ManagedQueueSender
{
   private String queueJndiName;
   
   private QueueSender queueSender;

   public String getQueueJndiName()
   {
      return queueJndiName;
   }

   public void setQueueJndiName(String jndiName)
   {
      this.queueJndiName = jndiName;
   }
   
   public Queue getQueue() throws NamingException
   {
      return (Queue) Naming.getInitialContext().lookup(queueJndiName);
   }
   
   @Create
   public void create() throws JMSException, NamingException
   {
      queueSender = org.jboss.seam.jms.QueueSession.instance().createSender( getQueue() );
   }
   
   @Destroy
   public void destroy() throws JMSException
   {
      queueSender.close();
   }
   
   @Unwrap
   public QueueSender getQueueSender()
   {
      return queueSender;
   }
   
}
