import typescript from '@rollup/plugin-typescript';

export default {
  input: 'src/index.ts',
  output: [
    {
      file: 'dist/index.js',
      format: 'es',
      sourcemap: true
    },
    {
      file: 'dist/index.umd.cjs',
      format: 'umd',
      name: 'JTPlayer',
      exports: 'named',
      sourcemap: true
    }
  ],
  plugins: [
    typescript({ tsconfig: './tsconfig.rollup.json' })
  ]
};
