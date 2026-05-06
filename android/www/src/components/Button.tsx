import type { ButtonHTMLAttributes, ReactNode } from 'react';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  size?: 'sm' | 'md' | 'full';
  children: ReactNode;
}

export function Button({ variant = 'secondary', size = 'md', className = '', children, ...props }: Props) {
  const btnClass = `btn btn-${variant} ${size !== 'md' ? `btn-${size}` : ''} ${className}`;
  return (
    <button className={btnClass.trim()} {...props}>
      {children}
    </button>
  );
}
