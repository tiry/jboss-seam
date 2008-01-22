package org.jboss.seam.example.seamspace;

import static org.jboss.seam.ScopeType.CONVERSATION;

import java.util.ArrayList;
import java.util.Date;

import javax.persistence.EntityManager;
import javax.persistence.NoResultException;

import org.jboss.seam.annotations.Begin;
import org.jboss.seam.annotations.End;
import org.jboss.seam.annotations.Factory;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Out;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.security.Restrict;

@Scope(CONVERSATION)
@Name("blog")
public class BlogAction
{    
   private String name;   
   private Integer blogId;
   
   @In
   private EntityManager entityManager;
   
   @In(required = false) @Out(required = false)
   private MemberBlog selectedBlog;
   
   @In(required = false) @Out(required = false, scope = CONVERSATION)
   private BlogComment comment;   
   
   @In(required = false)
   private Member authenticatedMember;
   
   /**
    * Used to read a single blog entry for a member
    */
   @Factory("selectedBlog") 
   @Begin(join=true)
   public void getBlog()
   {     
      try
      {
         selectedBlog = (MemberBlog) entityManager.createQuery(
           "from MemberBlog b where b.blogId = :blogId and b.member.memberName = :memberName")
           .setParameter("blogId", blogId)
           .setParameter("memberName", name)
           .getSingleResult();
      }
      catch (NoResultException ex) { }
   }
   
   @Factory("comment") @Restrict @Begin(join = true)
   public void createComment()
   {      
      System.out.println("Params - blogId: " + blogId + " name: " + name);
      
      comment = new BlogComment();
      comment.setCommentor(authenticatedMember);
      
      if (selectedBlog == null && name != null && blogId != null)
         getBlog();         
      
      comment.setBlog(selectedBlog);
   }
   
   @End
   public void saveComment()
   {      
      comment.setCommentDate(new Date());
      entityManager.persist(comment);
      
      // Reload the blog entry
      selectedBlog = (MemberBlog) entityManager.find(MemberBlog.class, 
            comment.getBlog().getBlogId());
   }     
   
   @Begin
   public void createEntry()
   {
      selectedBlog = new MemberBlog();              
   }
   
   @End
   public void saveEntry()
   {
      selectedBlog.setMember(authenticatedMember);
      selectedBlog.setEntryDate(new Date());
      selectedBlog.setComments(new ArrayList<BlogComment>());
      
      entityManager.persist(selectedBlog);
   }
   
   public String getName()
   {
      return name;
   }
   
   public void setName(String name)
   {
      this.name = name;
   }
   
   public Integer getBlogId()
   {
      return blogId;
   }
   
   public void setBlogId(Integer blogId)
   {
      this.blogId = blogId;
   }
}
