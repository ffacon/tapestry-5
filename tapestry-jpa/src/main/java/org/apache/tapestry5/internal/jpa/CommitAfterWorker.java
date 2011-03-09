// Copyright 2011 The Apache Software Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.internal.jpa;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.PersistenceUnit;

import org.apache.tapestry5.jpa.CommitAfter;
import org.apache.tapestry5.jpa.EntityManagerManager;
import org.apache.tapestry5.model.MutableComponentModel;
import org.apache.tapestry5.services.ClassTransformation;
import org.apache.tapestry5.services.ComponentClassTransformWorker;
import org.apache.tapestry5.services.ComponentMethodAdvice;
import org.apache.tapestry5.services.ComponentMethodInvocation;
import org.apache.tapestry5.services.TransformMethod;

public class CommitAfterWorker implements ComponentClassTransformWorker
{
    private final EntityManagerManager manager;

    private final ComponentMethodAdvice advice = new ComponentMethodAdvice()
    {
        public void advise(final ComponentMethodInvocation invocation)
        {

            final EntityTransaction transaction = getTransaction(invocation);

            if (transaction != null && !transaction.isActive())
            {
                transaction.begin();
            }

            try
            {
                invocation.proceed();

                // Success or checked exception:

                if (transaction != null && transaction.isActive())
                {
                    transaction.commit();
                }
            }
            catch (final RuntimeException e)
            {
                if (transaction != null && transaction.isActive())
                {
                    transaction.rollback();
                }

                throw e;
            }
        }

        private EntityTransaction getTransaction(final ComponentMethodInvocation invocation)
        {

            final PersistenceUnit persistenceUnit = invocation
                    .getMethodAnnotation(PersistenceUnit.class);

            if (persistenceUnit == null)
                return null;

            final String unitName = persistenceUnit.unitName();

            if (unitName == null)
                return null;

            final EntityManager em = manager.getEntityManager(unitName);

            return em.getTransaction();
        }

    };

    public CommitAfterWorker(final EntityManagerManager manager)
    {
        this.manager = manager;
    }

    public void transform(final ClassTransformation transformation,
            final MutableComponentModel model)
    {
        for (final TransformMethod method : transformation
                .matchMethodsWithAnnotation(CommitAfter.class))
        {
            method.addAdvice(advice);
        }
    }
}
